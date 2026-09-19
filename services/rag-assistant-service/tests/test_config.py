from assistant.config import Settings


def test_defaults_and_overrides(monkeypatch):
    for name in ("GROQ_API_KEY", "GEMINI_API_KEY", "GROQ_MODEL", "GROUNDEDNESS_THRESHOLD",
                 "RETRIEVAL_TOP_K", "CLAIMS_INTAKE_URL"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("MONGODB_URI", "mongodb://x")

    settings = Settings.from_env()

    assert settings.claims_intake_url == "http://claims-intake-service:8082"
    assert settings.groundedness_threshold == 0.5
    assert settings.retrieval_top_k == 3
    assert settings.groq_api_key == ""

    monkeypatch.setenv("RETRIEVAL_TOP_K", "5")
    monkeypatch.setenv("GROUNDEDNESS_THRESHOLD", "0.7")
    settings = Settings.from_env()
    assert settings.retrieval_top_k == 5
    assert settings.groundedness_threshold == 0.7


def test_blank_variable_falls_back_to_default(monkeypatch):
    monkeypatch.setenv("MONGODB_URI", "mongodb://x")
    monkeypatch.setenv("GROQ_MODEL", "")
    assert Settings.from_env().groq_model == "openai/gpt-oss-120b"
