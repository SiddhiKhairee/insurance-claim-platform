"""Chunks the synthetic policy_docs/*.md files by section, embeds each chunk with a local
sentence-transformers model, and upserts the result into the `policy_documents` collection.

Re-runnable: each chunk is upserted by a stable `docId` (f"{planType}_{section_slug}"), so
running this script again after editing a policy doc updates the existing chunks in place
rather than duplicating them.
"""

import os
import re
from pathlib import Path

from dotenv import load_dotenv
from pymongo import MongoClient
from sentence_transformers import SentenceTransformer

DATABASE_NAME = "claims_platform"
COLLECTION_NAME = "policy_documents"
MODEL_NAME = "all-MiniLM-L6-v2"
POLICY_DOCS_DIR = Path(__file__).parent / "policy_docs"

SECTION_PATTERN = re.compile(r"^##\s+(.+)$", re.MULTILINE)


def slugify(heading: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", heading.lower()).strip("-")


def chunk_sections(markdown_text: str) -> list[tuple[str, str]]:
    """Splits a policy doc into (heading, body) chunks on '## ' headers.

    The '# Title' line and the synthetic-data disclaimer blockquote above the first '##'
    are intentionally not chunked — they carry no plan-specific coverage content for
    retrieval.
    """
    matches = list(SECTION_PATTERN.finditer(markdown_text))
    chunks = []
    for i, match in enumerate(matches):
        heading = match.group(1).strip()
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(markdown_text)
        body = markdown_text[start:end].strip()
        chunks.append((heading, body))
    return chunks


def main() -> None:
    load_dotenv()
    client = MongoClient(os.environ["MONGODB_URI"])
    collection = client[DATABASE_NAME][COLLECTION_NAME]

    print(f"Loading embedding model {MODEL_NAME}...")
    model = SentenceTransformer(MODEL_NAME)

    total_chunks = 0
    for doc_path in sorted(POLICY_DOCS_DIR.glob("*.md")):
        plan_type = doc_path.stem
        markdown_text = doc_path.read_text(encoding="utf-8")
        chunks = chunk_sections(markdown_text)

        for heading, body in chunks:
            embedding = model.encode(body).tolist()
            doc_id = f"{plan_type}_{slugify(heading)}"
            collection.replace_one(
                {"docId": doc_id},
                {
                    "docId": doc_id,
                    "planType": plan_type,
                    "section": heading,
                    "text": body,
                    "embedding": embedding,
                },
                upsert=True,
            )
            word_count = len(body.split())
            print(f"{doc_id}: {word_count} words (~{round(word_count * 1.3)} tokens), embedded")
            total_chunks += 1

    print(f"Upserted {total_chunks} policy_documents chunks from {POLICY_DOCS_DIR}")


if __name__ == "__main__":
    main()
