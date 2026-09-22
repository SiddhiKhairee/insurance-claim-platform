from fakes import CORRECT_ANSWER, FakeProvider, make_assistant
from fastapi.testclient import TestClient

from main import create_app


def client_for(monkeypatch, origin: str | None) -> TestClient:
    if origin is None:
        monkeypatch.delenv("CORS_ALLOWED_ORIGIN", raising=False)
    else:
        monkeypatch.setenv("CORS_ALLOWED_ORIGIN", origin)
    assistant, _ = make_assistant(providers=[FakeProvider("groq", [CORRECT_ANSWER])])
    return TestClient(create_app(loader=lambda: assistant, background=False))


def preflight(client: TestClient, origin: str):
    return client.options(
        "/assistant/ask",
        headers={
            "Origin": origin,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )


def test_preflight_allows_the_configured_origin(monkeypatch):
    with client_for(monkeypatch, "https://claims.example.test") as client:
        response = preflight(client, "https://claims.example.test")

    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://claims.example.test"
    assert "POST" in response.headers["access-control-allow-methods"]


def test_preflight_does_not_echo_a_different_origin(monkeypatch):
    with client_for(monkeypatch, "https://claims.example.test") as client:
        response = preflight(client, "https://evil.example.test")

    assert "access-control-allow-origin" not in response.headers


def test_default_origin_is_the_local_frontend(monkeypatch):
    with client_for(monkeypatch, None) as client:
        response = preflight(client, "http://localhost:3000")

    assert response.headers["access-control-allow-origin"] == "http://localhost:3000"


def test_blank_env_var_falls_back_to_the_default(monkeypatch):
    with client_for(monkeypatch, "") as client:
        response = preflight(client, "http://localhost:3000")

    assert response.headers["access-control-allow-origin"] == "http://localhost:3000"


def test_actual_response_carries_the_allow_origin_header(monkeypatch):
    with client_for(monkeypatch, "https://claims.example.test") as client:
        response = client.post(
            "/assistant/ask",
            json={"question": "What is the maximum benefit for a dental claim?"},
            headers={"Origin": "https://claims.example.test"},
        )

    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "https://claims.example.test"
