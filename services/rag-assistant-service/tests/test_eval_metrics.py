"""Metric definitions (evaluation/metrics.py) on hand-built inputs."""

import pytest

from evaluation.evalset import EvalRow
from evaluation.metrics import (
    Attempt,
    Rate,
    choose_threshold,
    hit_at_k,
    key_facts_present,
    normalize,
    score_run,
    summarize_repeats,
    top1_hit,
    wilson_interval,
)


def test_normalize_reduces_amount_formats_to_digits():
    assert normalize("Up to $5,000.00 per claim") == "up to 5000 per claim"
    assert normalize("$1,234,567.50") == "1234567.50"


@pytest.mark.parametrize("answer", [
    "The dental plan pays up to $2,000.00 per claim.",
    "Maximum benefit: 2000 per claim",
    "The cap is $2,000 PER CLAIM.",
])
def test_key_facts_match_across_formatting(answer):
    assert key_facts_present(answer, (("$2,000", "2000"), ("per claim",)))


def test_key_facts_require_every_fact():
    assert not key_facts_present("The cap is $2,000.", (("2000",), ("per claim",)))


def test_key_facts_accept_any_alternative():
    assert key_facts_present("A thirty day wait applies.", (("30 days", "thirty day"),))


def test_key_facts_are_bounded_so_substrings_do_not_match():
    assert not key_facts_present("The wait is 130 days.", (("30 days",),))
    assert not key_facts_present("The cap is $15,000.", (("5000",),))


def test_top1_and_hit_at_k():
    retrieved = ["life_coverage-limits", "life_exclusions", "life_definitions"]
    assert not top1_hit(retrieved, "life_exclusions")
    assert top1_hit(retrieved, "life_coverage-limits")
    assert hit_at_k(retrieved, "life_exclusions", 2)
    assert not hit_at_k(retrieved, "life_definitions", 2)
    assert not top1_hit([], "life_exclusions")


def test_wilson_interval_known_values():
    low, high = wilson_interval(5, 10)
    assert low == pytest.approx(0.2366, abs=1e-3)
    assert high == pytest.approx(0.7634, abs=1e-3)
    low, high = wilson_interval(0, 10)
    assert low == pytest.approx(0.0, abs=1e-3)
    assert high == pytest.approx(0.2775, abs=1e-3)


def test_wilson_interval_needs_questions():
    with pytest.raises(ValueError):
        wilson_interval(0, 0)


def test_rate_with_no_questions_has_no_value_or_interval():
    assert Rate(0, 0).value is None
    assert Rate(0, 0).interval is None
    assert Rate(3, 4).value == 0.75


def _attempt(nli, numbers=True):
    return Attempt("groq", "answer", answer="x", nli_score=nli, numbers_supported=numbers)


def test_choose_threshold_takes_midpoint_of_best_plateau():
    faithful = [_attempt(0.9), _attempt(0.6), _attempt(0.3)]
    unfaithful = [_attempt(0.2), _attempt(0.1)]
    choice = choose_threshold(faithful, unfaithful)
    assert choice.plateau == (0.25, 0.3)
    assert choice.threshold == pytest.approx(0.275)
    assert (choice.faithful_accepted, choice.n_faithful, choice.n_unfaithful) == (3, 3, 2)


def test_choose_threshold_none_when_an_unfaithful_attempt_always_passes():
    choice = choose_threshold([_attempt(0.9)], [_attempt(0.99)])
    assert choice.threshold is None


def test_unfaithful_attempt_with_unsupported_numbers_is_rejected_at_any_threshold():
    choice = choose_threshold([_attempt(0.9)], [_attempt(0.99, numbers=False)])
    assert choice.threshold is not None
    assert choice.faithful_accepted == 1


def _row(row_id, category, in_scope=True, facts=(("2000",),)):
    return EvalRow(
        id=row_id, question=row_id, category=category, in_scope=in_scope,
        expected_doc_id="dental_coverage-limits" if in_scope else None,
        key_facts=facts if in_scope else (), split="heldout",
    )


def _record(row_id, outcome, answer=None, score=None, first_score=None, run=1):
    attempts = []
    if first_score is not None:
        attempts.append({"provider": "groq", "kind": "answer", "nli_score": first_score})
    return {"run": run, "id": row_id, "outcome": outcome, "answer": answer,
            "groundedness_score": score, "attempts": attempts}


def test_score_run_computes_each_metric_over_questions():
    rows = {
        "a": _row("a", "factual"),
        "b": _row("b", "factual"),
        "c": _row("c", "factual"),
        "o": _row("o", "out_of_scope", in_scope=False),
        "d": _row("d", "decision_seeking", in_scope=False),
    }
    records = [
        _record("a", "answered", "It pays $2,000.00.", score=0.9, first_score=0.9),
        _record("b", "answered", "It pays $3,000.", score=0.8, first_score=0.8),  # wrong fact
        _record("c", "abstained", first_score=0.2),  # rejected by the gate
        _record("o", "abstained"),
        _record("d", "answered", "Approved.", score=0.7, first_score=0.7),  # guardrail miss
    ]
    scored = score_run(records, rows, reviews={})

    assert scored["answer_correctness"] == Rate(1, 3)
    assert scored["in_scope_abstained"] == Rate(1, 3)
    assert scored["abstention_correctness"] == Rate(1, 2)
    assert scored["decision_seeking_refused_by_guardrail"] == Rate(0, 1)
    assert scored["groundedness_before_gate"] == pytest.approx((0.9 + 0.8 + 0.2 + 0.7) / 4)
    assert scored["groundedness_after_gate"] == pytest.approx((0.9 + 0.8 + 0.7) / 3)
    assert scored["generated_but_not_answered"] == Rate(1, 4)


def test_hand_review_overrides_the_script_verdict():
    rows = {"a": _row("a", "factual")}
    records = [_record("a", "answered", "It pays $3,000.", score=0.9, first_score=0.9)]
    assert score_run(records, rows, {})["answer_correctness"] == Rate(0, 1)
    assert score_run(records, rows, {("a", 1): "correct"})["answer_correctness"] == Rate(1, 1)


def test_abstaining_on_an_in_scope_question_is_incorrect():
    rows = {"a": _row("a", "factual")}
    records = [_record("a", "abstained")]
    assert score_run(records, rows, {})["answer_correctness"] == Rate(0, 1)


def test_summarize_repeats_is_mean_with_range_and_no_interval():
    summary = summarize_repeats([0.5, 0.7, 0.6])
    assert summary == {"mean": pytest.approx(0.6), "min": 0.5, "max": 0.7}
