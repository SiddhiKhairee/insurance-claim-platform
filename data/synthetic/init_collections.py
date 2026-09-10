"""One-time, idempotent setup: creates the platform's MongoDB collections."""

import os

from dotenv import load_dotenv
from pymongo import MongoClient

DATABASE_NAME = "claims_platform"
COLLECTIONS = ["enrollments", "claims", "policy_documents", "notifications_log"]


def main() -> None:
    load_dotenv()
    client = MongoClient(os.environ["MONGODB_URI"])
    db = client[DATABASE_NAME]
    existing = set(db.list_collection_names())
    for name in COLLECTIONS:
        if name in existing:
            print(f"{name}: already exists")
        else:
            db.create_collection(name)
            print(f"{name}: created")


if __name__ == "__main__":
    main()
