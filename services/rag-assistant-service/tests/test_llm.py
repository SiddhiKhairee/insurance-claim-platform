import json

import httpx
import pytest
from fakes import DENIED_CLAIM, DENTAL_LIMITS

from assistant.llm import (
    INSUFFICIENT_CONTEXT,
    SYSTEM_PROMPT,
    GeminiProvider,
    GroqProvider,
    ProviderError,
    build_user_prompt,
)
from assistant.premises import premise_from_chunk, premise_from_claim


def groq(handler, key="k"):
    return GroqProvider(key, "groq-model", 5, transport=httpx.MockTransport(handler))


def gemini(handler, key="k"):
    return GeminiProvider(key, "gemini-model", 5, transport=httpx.MockTransport(handler))


def test_groq_request_and_response_parsing():
    seen = {}

    def handler(request):
        seen["auth"] = request.headers["authorization"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": " Hello. "}}]})

    assert groq(handler, key="secret").generate("sys", "usr") == "Hello."
    assert seen["auth"] == "Bearer secret"
    assert seen["body"]["model"] == "groq-model"
    assert seen["body"]["temperature"] == 0
    assert seen["body"]["messages"] == [
        {"role": "system", "content": "sys"},
        {"role": "user", "content": "usr"},
    ]


def test_gemini_request_and_response_parsing():
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["key"] = request.headers["x-goog-api-key"]
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200, json={"candidates": [{"content": {"parts": [{"text": "Hi there."}]}}]}
        )

    assert gemini(handler, key="secret").generate("sys", "usr") == "Hi there."
    assert seen["url"].endswith("/models/gemini-model:generateContent")
    assert seen["key"] == "secret"
    assert seen["body"]["generationConfig"]["temperature"] == 0
    assert seen["body"]["systemInstruction"]["parts"][0]["text"] == "sys"
    assert seen["body"]["contents"][0]["parts"][0]["text"] == "usr"


def test_gemini_joins_text_parts_and_skips_thinking_parts():
    parts = [
        {"text": "internal reasoning", "thought": True},
        {"text": "The Group Dental Plan pays up to $2,000. "},
        {"text": "It has no other limit.", "thoughtSignature": "abc"},
    ]

    def handler(request):
        return httpx.Response(200, json={"candidates": [{"content": {"parts": parts}}]})

    assert gemini(handler).generate("s", "u") == (
        "The Group Dental Plan pays up to $2,000. It has no other limit."
    )


@pytest.mark.parametrize("make", [groq, gemini])
def test_missing_key_is_a_provider_error(make):
    with pytest.raises(ProviderError, match="not configured"):
        make(lambda request: httpx.Response(200), key="").generate("s", "u")


@pytest.mark.parametrize("make", [groq, gemini])
@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(429, json={"error": "rate limited"}),
        httpx.Response(500),
        httpx.Response(200, json={"unexpected": "shape"}),
        httpx.Response(200, content=b"not json"),
    ],
)
def test_http_errors_and_malformed_bodies_are_provider_errors(make, response):
    with pytest.raises(ProviderError):
        make(lambda request: response).generate("s", "u")


@pytest.mark.parametrize("make", [groq, gemini])
def test_timeout_is_a_provider_error(make):
    def handler(request):
        raise httpx.ReadTimeout("timed out")

    with pytest.raises(ProviderError):
        make(handler).generate("s", "u")


def test_empty_answer_is_a_provider_error():
    def handler(request):
        return httpx.Response(200, json={"choices": [{"message": {"content": "  "}}]})

    with pytest.raises(ProviderError, match="empty"):
        groq(handler).generate("s", "u")


def test_prompt_contains_numbered_context_and_delimited_question():
    prompt = build_user_prompt(
        "What is the dental limit?",
        [premise_from_chunk(DENTAL_LIMITS), premise_from_claim(DENIED_CLAIM)],
    )
    assert "[1] (policy: dental / Coverage Limits)" in prompt
    assert DENTAL_LIMITS.text in prompt
    assert "[2] (claim record)" in prompt
    assert "<question>\nWhat is the dental limit?\n</question>" in prompt


def test_system_prompt_rules():
    assert INSUFFICIENT_CONTEXT in SYSTEM_PROMPT
    assert "No preamble" in SYSTEM_PROMPT
    assert "self-contained" in SYSTEM_PROMPT
    assert "never make or change an adjudication decision" in SYSTEM_PROMPT
