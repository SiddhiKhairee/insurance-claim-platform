"""Debugging/verification utility: embeds a question with the same model used for ingestion
and runs a real $vectorSearch query against the Atlas index, to prove it returns relevant
chunks rather than just existing. Pass a question as an argument, or run with no arguments
for the default dental question."""

import argparse
import os

from dotenv import load_dotenv
from pymongo import MongoClient
from sentence_transformers import SentenceTransformer

DATABASE_NAME = "claims_platform"
COLLECTION_NAME = "policy_documents"
INDEX_NAME = "policy_documents_vector_index"
MODEL_NAME = "all-MiniLM-L6-v2"

DEFAULT_QUESTION = "What is the maximum benefit for a dental claim?"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a $vectorSearch query against policy_documents.")
    parser.add_argument("question", nargs="?", default=DEFAULT_QUESTION)
    args = parser.parse_args()

    load_dotenv()
    client = MongoClient(os.environ["MONGODB_URI"])
    collection = client[DATABASE_NAME][COLLECTION_NAME]
    model = SentenceTransformer(MODEL_NAME)

    query_embedding = model.encode(args.question).tolist()

    pipeline = [
        {
            "$vectorSearch": {
                "index": INDEX_NAME,
                "path": "embedding",
                "queryVector": query_embedding,
                "numCandidates": 100,
                "limit": 3,
            }
        },
        {
            "$project": {
                "_id": 0,
                "docId": 1,
                "planType": 1,
                "section": 1,
                "score": {"$meta": "vectorSearchScore"},
            }
        },
    ]

    print(f"Question: {args.question}\n")
    for result in collection.aggregate(pipeline):
        print(result)


if __name__ == "__main__":
    main()
