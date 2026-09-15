"""One-time, idempotent setup: creates the Atlas Vector Search index on
policy_documents.embedding and waits for it to become queryable.

Atlas builds search indexes asynchronously, so "created" and "queryable" are different
moments — this script polls until the index actually reports queryable: true (or times
out) rather than assuming success once the create call returns.
"""

import os
import time

from dotenv import load_dotenv
from pymongo import MongoClient
from pymongo.operations import SearchIndexModel

DATABASE_NAME = "claims_platform"
COLLECTION_NAME = "policy_documents"
INDEX_NAME = "policy_documents_vector_index"
EMBEDDING_DIMENSIONS = 384
POLL_INTERVAL_SECONDS = 5
POLL_TIMEOUT_SECONDS = 180


def find_index(collection, name: str) -> dict | None:
    for index in collection.list_search_indexes():
        if index["name"] == name:
            return index
    return None


def wait_until_queryable(collection, name: str) -> None:
    deadline = time.monotonic() + POLL_TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        index = find_index(collection, name)
        if index and index.get("queryable"):
            print(f"{name}: queryable")
            return
        status = index.get("status") if index else "not found yet"
        print(f"{name}: status={status}, waiting...")
        time.sleep(POLL_INTERVAL_SECONDS)
    raise TimeoutError(f"{name} did not become queryable within {POLL_TIMEOUT_SECONDS}s")


def main() -> None:
    load_dotenv()
    client = MongoClient(os.environ["MONGODB_URI"])
    collection = client[DATABASE_NAME][COLLECTION_NAME]

    existing = find_index(collection, INDEX_NAME)
    if existing:
        print(f"{INDEX_NAME}: already exists (status={existing.get('status')})")
    else:
        model = SearchIndexModel(
            name=INDEX_NAME,
            type="vectorSearch",
            definition={
                "fields": [
                    {
                        "type": "vector",
                        "path": "embedding",
                        "numDimensions": EMBEDDING_DIMENSIONS,
                        "similarity": "cosine",
                    },
                    {"type": "filter", "path": "planType"},
                ]
            },
        )
        collection.create_search_index(model)
        print(f"{INDEX_NAME}: create requested")

    wait_until_queryable(collection, INDEX_NAME)


if __name__ == "__main__":
    main()
