"""One-off verification script (not part of the phase's permanent scripts): embeds a sample
question with the same model used for ingestion and runs a real $vectorSearch query against
the Atlas index, to prove it returns a relevant chunk rather than just existing."""

import os

from dotenv import load_dotenv
from pymongo import MongoClient
from sentence_transformers import SentenceTransformer

DATABASE_NAME = "claims_platform"
COLLECTION_NAME = "policy_documents"
INDEX_NAME = "policy_documents_vector_index"
MODEL_NAME = "all-MiniLM-L6-v2"

QUESTION = "What is the maximum benefit for a dental claim?"

load_dotenv()
client = MongoClient(os.environ["MONGODB_URI"])
collection = client[DATABASE_NAME][COLLECTION_NAME]
model = SentenceTransformer(MODEL_NAME)

query_embedding = model.encode(QUESTION).tolist()

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

print(f"Question: {QUESTION}\n")
for result in collection.aggregate(pipeline):
    print(result)
