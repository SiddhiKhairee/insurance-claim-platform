"""Retrieval: query embedding + MongoDB Atlas `$vectorSearch` over `policy_documents`.

Uses the same `all-MiniLM-L6-v2` model as the Phase 6 ingestion script
(`data/synthetic/generate_policy_docs.py`) — query and chunk embeddings must come from the same
model. Known limit (2026-09-15 §12): a small general-purpose embedding model can rank closely
related chunks imperfectly (life plan: coverage-limits 0.798 vs exclusions 0.785).
"""

import re
from dataclasses import dataclass
from typing import Protocol

INDEX_NAME = "policy_documents_vector_index"
EMBEDDING_MODEL = "all-MiniLM-L6-v2"
NUM_CANDIDATES = 100

PLAN_TYPES = ("dental", "vision", "disability", "life")

_PLAN_KEYWORDS = {
    "dental": re.compile(r"\b(?:dental|dentist|teeth|tooth|orthodont\w*)\b"),
    "vision": re.compile(r"\b(?:vision|eyeglasses|glasses|contacts?|optical|eye exam)\b"),
    "disability": re.compile(r"\b(?:disability|disabled)\b"),
    "life": re.compile(r"\b(?:life|beneficiar\w*|death)\b"),
}


@dataclass(frozen=True)
class Chunk:
    doc_id: str
    plan_type: str
    section: str
    text: str
    score: float


class Embedder(Protocol):
    def embed(self, text: str) -> list[float]: ...


class VectorStore(Protocol):
    def search(self, vector: list[float], k: int, plan_type: str | None) -> list[Chunk]: ...


def infer_plan_type(question: str) -> str | None:
    """Keyword-based planType inference; None when no plan or more than one plan is mentioned."""
    text = question.lower()
    matched = [plan for plan, pattern in _PLAN_KEYWORDS.items() if pattern.search(text)]
    return matched[0] if len(matched) == 1 else None


class SentenceTransformerEmbedder:
    def __init__(self, model_name: str = EMBEDDING_MODEL) -> None:
        from sentence_transformers import SentenceTransformer  # lazy: keeps CI free of torch

        self._model = SentenceTransformer(model_name)

    def embed(self, text: str) -> list[float]:
        return self._model.encode(text).tolist()


class MongoVectorStore:
    def __init__(self, collection, index_name: str = INDEX_NAME) -> None:
        self._collection = collection
        self._index_name = index_name

    def search(self, vector: list[float], k: int, plan_type: str | None) -> list[Chunk]:
        stage = {
            "index": self._index_name,
            "path": "embedding",
            "queryVector": vector,
            "numCandidates": NUM_CANDIDATES,
            "limit": k,
        }
        if plan_type:
            stage["filter"] = {"planType": plan_type}
        pipeline = [
            {"$vectorSearch": stage},
            {
                "$project": {
                    "_id": 0,
                    "docId": 1,
                    "planType": 1,
                    "section": 1,
                    "text": 1,
                    "score": {"$meta": "vectorSearchScore"},
                }
            },
        ]
        return [
            Chunk(
                doc_id=doc["docId"],
                plan_type=doc["planType"],
                section=doc["section"],
                text=doc["text"],
                score=float(doc["score"]),
            )
            for doc in self._collection.aggregate(pipeline)
        ]
