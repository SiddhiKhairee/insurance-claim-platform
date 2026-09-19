import pytest

from assistant.retrieval import MongoVectorStore, infer_plan_type


@pytest.mark.parametrize(
    "question, expected",
    [
        ("What is the maximum benefit for a dental claim?", "dental"),
        ("Does the plan cover eyeglasses?", "vision"),
        ("What is the waiting period for disability coverage?", "disability"),
        ("Is suicide excluded from the life insurance benefit?", "life"),
        ("What is the maximum benefit?", None),
        ("Compare dental and vision limits", None),
        ("Do you cover supervision costs?", None),
    ],
)
def test_infer_plan_type(question, expected):
    assert infer_plan_type(question) == expected


class FakeCollection:
    def __init__(self, docs):
        self.docs = docs
        self.pipeline = None

    def aggregate(self, pipeline):
        self.pipeline = pipeline
        return iter(self.docs)


DOC = {
    "docId": "dental_coverage-limits",
    "planType": "dental",
    "section": "Coverage Limits",
    "text": "text",
    "score": 0.85,
}


def test_vector_store_builds_filtered_vector_search():
    collection = FakeCollection([DOC])
    chunks = MongoVectorStore(collection).search([0.1, 0.2], k=3, plan_type="dental")

    stage = collection.pipeline[0]["$vectorSearch"]
    assert stage["index"] == "policy_documents_vector_index"
    assert stage["path"] == "embedding"
    assert stage["queryVector"] == [0.1, 0.2]
    assert stage["limit"] == 3
    assert stage["filter"] == {"planType": "dental"}
    assert chunks[0].doc_id == "dental_coverage-limits"
    assert chunks[0].score == 0.85


def test_vector_store_omits_filter_when_plan_unknown():
    collection = FakeCollection([DOC])
    MongoVectorStore(collection).search([0.1], k=3, plan_type=None)
    assert "filter" not in collection.pipeline[0]["$vectorSearch"]
