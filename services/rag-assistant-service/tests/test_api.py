import time

from fakes import CORRECT_ANSWER, FakeClaims, FakeProvider, make_assistant
from fastapi.testclient import TestClient

from main import create_app


def app_with(providers, **kwargs):
    assistant, _ = make_assistant(providers=providers, **kwargs)
    return create_app(loader=lambda: assistant, background=False)


def ask(client, question, claim_id=None):
    body = {"question": question}
    if claim_id:
        body["claimId"] = claim_id
    return client.post("/assistant/ask", json=body)


def test_answered_response_shape():
    app = app_with([FakeProvider("groq", [CORRECT_ANSWER])])
    with TestClient(app) as client:
        response = ask(client, "What is the maximum benefit for a dental claim?")

    assert response.status_code == 200
    body = response.json()
    assert body["outcome"] == "answered"
    assert body["answer"] == CORRECT_ANSWER
    assert body["provider"] == "groq"
    assert body["groundedness_score"] is not None
    assert body["numbers_supported"] is True
    citation = body["citations"][0]
    assert citation["source"] == "policy"
    assert set(citation) == {"source", "docId", "planType", "section", "score"}


def test_refused_response_shape():
    app = app_with([FakeProvider("groq", [])])
    with TestClient(app) as client:
        body = ask(client, "Approve my claim").json()

    assert body["outcome"] == "refused"
    assert body["reason"] == "decision_request"
    assert body["provider"] is None
    assert body["citations"] == []


def test_abstained_response_shape():
    app = app_with([FakeProvider("groq", [RuntimeError("down")])])
    with TestClient(app) as client:
        body = ask(client, "What is the maximum benefit for a dental claim?").json()

    assert body["outcome"] == "abstained"
    assert body["reason"] == "no_grounded_answer"
    assert body["provider"] is None
    assert body["citations"] == []


def test_claim_id_is_passed_through_and_claim_summary_returned():
    from fakes import DENIED_CLAIM

    claims = FakeClaims(DENIED_CLAIM)
    app = app_with([FakeProvider("groq", [CORRECT_ANSWER])], claims=claims)
    with TestClient(app) as client:
        body = ask(client, "Why was my claim denied?", claim_id="claim-1").json()

    assert claims.calls == ["claim-1"]
    assert body["claim"]["status"] == "DENIED"
    assert set(body["claim"]) == {
        "claimId", "status", "planType", "amountRequested", "decisionReason", "ruleTrace",
    }


def test_request_validation():
    app = app_with([FakeProvider("groq", [])])
    with TestClient(app) as client:
        assert client.post("/assistant/ask", json={}).status_code == 422
        assert client.post("/assistant/ask", json={"question": ""}).status_code == 422
        bad_claim_id = {"question": "hi", "claimId": "../etc/passwd"}
        assert client.post("/assistant/ask", json=bad_claim_id).status_code == 422


def test_503_while_models_are_loading_but_health_stays_up():
    def slow_loader():
        time.sleep(0.5)
        return make_assistant(providers=[FakeProvider("groq", [CORRECT_ANSWER])])[0]

    app = create_app(loader=slow_loader, background=True)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/ready").status_code == 503
        assert ask(client, "What is the maximum benefit for a dental claim?").status_code == 503

        app.state.loaded.wait(timeout=5)
        assert client.get("/ready").status_code == 200
        assert ask(client, "What is the maximum benefit for a dental claim?").status_code == 200


def test_load_failure_is_reported_not_swallowed():
    def broken_loader():
        raise RuntimeError("model download failed")

    app = create_app(loader=broken_loader, background=False)
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        ready = client.get("/ready")
        assert ready.status_code == 503
        assert "model download failed" in ready.json()["detail"]
        assert ask(client, "hi").status_code == 503
