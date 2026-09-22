"""The recorders capture the real graph's behaviour, and `simulate_chain` reproduces the real
graph's outcome from those recorded attempts (the calibration sweep relies on that parity)."""

import pytest
from fakes import (
    CORRECT_ANSWER,
    HALLUCINATED_ANSWER,
    FakeNLI,
    FakeProvider,
    make_assistant,
)

from assistant.graph import Assistant
from evaluation.metrics import simulate_chain
from evaluation.recording import instrument

LOW = "The Group Dental Plan covers orthodontics for adults."  # NLI 0.1 (unsupported)
MID = "The Group Dental Plan covers fillings."  # NLI 0.6: passes 0.5, fails 0.7
INSUFFICIENT = "INSUFFICIENT_CONTEXT"
FAIL = RuntimeError("boom")  # FakeProvider raises this as a ProviderError


def nli_by_text(_premise: str, hypothesis: str) -> float:
    if "orthodontics" in hypothesis:
        return 0.1
    if "fillings" in hypothesis:
        return 0.6
    return 0.99


SCENARIOS = {
    "first provider passes": ([CORRECT_ANSWER], [CORRECT_ANSWER]),
    "first fails gate, second passes": ([LOW], [CORRECT_ANSWER]),
    "both fail gate": ([LOW], [LOW]),
    "insufficient context first": ([INSUFFICIENT], [CORRECT_ANSWER]),
    "first errors, second passes": ([FAIL], [CORRECT_ANSWER]),
    "rejected, then insufficient": ([LOW], [INSUFFICIENT]),
    "fabricated number despite high NLI": ([HALLUCINATED_ANSWER], [CORRECT_ANSWER]),
    "fabricated number twice": ([HALLUCINATED_ANSWER], [HALLUCINATED_ANSWER]),
    "both providers error": ([FAIL], [FAIL]),
    "middling score": ([MID], [CORRECT_ANSWER]),
}


def build(first, second, *, threshold, always_reject=False):
    _, deps = make_assistant(
        providers=[FakeProvider("groq", first), FakeProvider("gemini", second)],
        nli=FakeNLI(score_fn=nli_by_text),
        threshold=threshold,
    )
    wrapped, trace = instrument(deps, always_reject=always_reject)
    return Assistant(wrapped), trace


@pytest.mark.parametrize("threshold", [0.5, 0.7])
@pytest.mark.parametrize("name", SCENARIOS)
def test_simulate_chain_matches_the_real_graph(name, threshold):
    first, second = SCENARIOS[name]
    assistant, trace = build(first, second, threshold=threshold)

    response = assistant.ask("What is the maximum benefit for a dental claim?")
    simulated = simulate_chain(trace.attempts, threshold)

    assert simulated.outcome == response.outcome
    assert simulated.reason == response.reason
    assert simulated.provider == response.provider
    assert simulated.groundedness_score == response.groundedness_score
    assert simulated.numbers_supported == response.numbers_supported
    if response.outcome == "answered":
        assert simulated.answer == response.answer


def test_always_reject_walks_the_whole_chain_and_records_every_attempt():
    assistant, trace = build([CORRECT_ANSWER], [CORRECT_ANSWER], threshold=0.5, always_reject=True)

    response = assistant.ask("What is the maximum benefit for a dental claim?")

    assert response.outcome == "abstained"
    assert [a.provider for a in trace.attempts] == ["groq", "gemini"]
    assert all(a.kind == "answer" and a.nli_score == 0.99 for a in trace.attempts)
    # The recorded attempts still let a threshold sweep see that this answer would have passed.
    assert simulate_chain(trace.attempts, 0.5).outcome == "answered"


def test_recorders_capture_retrieval_and_attempt_kinds():
    assistant, trace = build([FAIL], [INSUFFICIENT], threshold=0.5)

    assistant.ask("What is the maximum benefit for a dental claim?")

    assert [(a.provider, a.kind) for a in trace.attempts] == [
        ("groq", "error"),
        ("gemini", "insufficient"),
    ]
    assert trace.searches == 1
    assert trace.plan_type == "dental"
    assert [c.doc_id for c in trace.chunks] == ["dental_coverage-limits", "dental_exclusions"]
    first_chunk = trace.retrieval_dict()["chunks"][0]
    assert first_chunk == {"doc_id": "dental_coverage-limits", "score": 0.85}


def test_trace_reset_clears_state_between_questions():
    assistant, trace = build([CORRECT_ANSWER], [], threshold=0.5)
    assistant.ask("What is the maximum benefit for a dental claim?")
    assert trace.attempts

    trace.reset()

    assert trace.attempts == [] and trace.chunks == [] and trace.searches == 0
