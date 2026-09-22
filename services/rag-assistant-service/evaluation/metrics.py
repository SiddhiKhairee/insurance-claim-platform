"""Metric definitions (PLAN.md section 11, fixed 2026-09-19) as pure functions over run records.

Nothing here touches the network or a model, so it is unit-tested in CI.

A *run record* is one JSON object per (question, repeat), as written by `run_eval.py`:
    run, id, outcome, reason, provider, answer, groundedness_score, numbers_supported,
    retrieval {plan_type, chunks: [{doc_id, score}]}, attempts [Attempt dicts]
"""

import math
import re
from dataclasses import asdict, dataclass
from statistics import mean

from evaluation.evalset import EvalRow

# ---------------------------------------------------------------------------------------------
# Retrieval
# ---------------------------------------------------------------------------------------------


def top1_hit(retrieved_doc_ids: list[str], expected_doc_id: str) -> bool:
    return bool(retrieved_doc_ids) and retrieved_doc_ids[0] == expected_doc_id


def hit_at_k(retrieved_doc_ids: list[str], expected_doc_id: str, k: int) -> bool:
    return expected_doc_id in retrieved_doc_ids[:k]


# ---------------------------------------------------------------------------------------------
# Answer correctness: the answer contains all of the row's key facts
# ---------------------------------------------------------------------------------------------

_DIGIT_COMMA = re.compile(r"(?<=\d),(?=\d{3})")
_TRAILING_ZEROS = re.compile(r"(?<=\d)\.00\b")


def normalize(text: str) -> str:
    """Case-folded, whitespace-collapsed text with `$5,000.00` reduced to `5000`, so a key fact
    written `5000` matches however the model formatted the amount."""
    text = text.casefold().replace("’", "'").replace("–", "-").replace("‑", "-")
    text = text.replace("$", "")
    text = _DIGIT_COMMA.sub("", text)
    text = _TRAILING_ZEROS.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def _contains(haystack: str, needle: str) -> bool:
    # Bounded on both sides so "30 days" does not match "130 days" and "5000" not "15000".
    return re.search(rf"(?<!\w){re.escape(needle)}(?!\w)", haystack) is not None


def key_facts_present(answer: str, key_facts: tuple[tuple[str, ...], ...]) -> bool:
    """True iff, for every key fact, at least one of its alternatives appears in the answer."""
    text = normalize(answer)
    return all(any(_contains(text, normalize(alt)) for alt in alternatives)
               for alternatives in key_facts)


# ---------------------------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------------------------


def wilson_interval(successes: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """95% Wilson score interval for a proportion. `n` is the number of QUESTIONS the rate is
    over; repeats of the same questions measure LLM variance and never add to n."""
    if n <= 0:
        raise ValueError("wilson_interval needs n > 0")
    p = successes / n
    denominator = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denominator
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denominator
    return max(0.0, centre - half), min(1.0, centre + half)


@dataclass(frozen=True)
class Rate:
    successes: int
    n: int

    @property
    def value(self) -> float | None:
        return self.successes / self.n if self.n else None

    @property
    def interval(self) -> tuple[float, float] | None:
        return wilson_interval(self.successes, self.n) if self.n else None


# ---------------------------------------------------------------------------------------------
# Provider-chain simulation (used only for threshold calibration)
# ---------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Attempt:
    """One provider call. `kind` is "answer" (went through the gate), "insufficient" (the model
    replied INSUFFICIENT_CONTEXT, gate skipped) or "error" (the provider failed)."""

    provider: str
    kind: str
    answer: str | None = None
    nli_score: float | None = None
    numbers_supported: bool | None = None
    unsupported_numbers: tuple[str, ...] = ()
    error: str | None = None

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, raw: dict) -> "Attempt":
        return cls(**{**raw, "unsupported_numbers": tuple(raw.get("unsupported_numbers", ()))})


@dataclass(frozen=True)
class ChainOutcome:
    outcome: str
    reason: str | None
    provider: str | None
    answer: str | None
    groundedness_score: float | None
    numbers_supported: bool | None


def gate_passes(attempt: Attempt, threshold: float) -> bool:
    """The gate's pass rule (`GroundednessGate.evaluate`): numbers supported AND NLI >= t."""
    return bool(
        attempt.numbers_supported
        and attempt.nli_score is not None
        and attempt.nli_score >= threshold
    )


def simulate_chain(attempts: list[Attempt], threshold: float) -> ChainOutcome:
    """What the graph's generate -> groundedness loop returns for these recorded provider
    attempts at `threshold`. Covers only that loop; retrieval-level abstentions (no chunks,
    claim lookup failures) happen before it and are not simulated. Parity with the real graph
    is pinned by tests/test_eval_recording.py."""
    gate_nli: float | None = None
    gate_numbers: bool | None = None
    for attempt in attempts:
        if attempt.kind == "error":
            continue
        if attempt.kind == "insufficient":
            return ChainOutcome(
                "abstained", "insufficient_context", attempt.provider, None, gate_nli, gate_numbers
            )
        gate_nli, gate_numbers = attempt.nli_score, attempt.numbers_supported
        if gate_passes(attempt, threshold):
            return ChainOutcome(
                "answered", None, attempt.provider, attempt.answer, gate_nli, gate_numbers
            )
    return ChainOutcome("abstained", "no_grounded_answer", None, None, gate_nli, gate_numbers)


# ---------------------------------------------------------------------------------------------
# Threshold selection (calibration criterion, fixed before any run)
# ---------------------------------------------------------------------------------------------

THRESHOLD_GRID = tuple(round(0.05 * i, 2) for i in range(1, 20))  # 0.05 ... 0.95


@dataclass(frozen=True)
class ThresholdChoice:
    threshold: float | None  # None: no threshold satisfies the constraint
    plateau: tuple[float, ...]  # the contiguous grid values that tie for the best acceptance
    faithful_accepted: int
    n_faithful: int
    n_unfaithful: int


def choose_threshold(
    faithful: list[Attempt], unfaithful: list[Attempt], grid: tuple[float, ...] = THRESHOLD_GRID
) -> ThresholdChoice:
    """Criterion: among thresholds that reject EVERY unfaithful attempt (real unfaithful answers
    and adversarial probes), take the one that accepts the most faithful attempts; on a plateau,
    the midpoint of the longest contiguous run of tied grid values. None if no threshold rejects
    every unfaithful attempt (the caller then keeps the provisional value and reports it)."""
    feasible = [
        (t, sum(gate_passes(a, t) for a in faithful))
        for t in grid
        if not any(gate_passes(a, t) for a in unfaithful)
    ]
    if not feasible:
        return ThresholdChoice(None, (), 0, len(faithful), len(unfaithful))

    best = max(accepted for _, accepted in feasible)
    tied = [t for t, accepted in feasible if accepted == best]
    # Longest contiguous run of tied grid values (adjacent grid steps).
    runs: list[list[float]] = [[tied[0]]]
    for previous, current in zip(tied, tied[1:], strict=False):
        if grid.index(current) - grid.index(previous) == 1:
            runs[-1].append(current)
        else:
            runs.append([current])
    plateau = max(runs, key=len)
    midpoint = round((plateau[0] + plateau[-1]) / 2, 3)
    return ThresholdChoice(midpoint, tuple(plateau), best, len(faithful), len(unfaithful))


# ---------------------------------------------------------------------------------------------
# Scoring one run of the held-out split
# ---------------------------------------------------------------------------------------------

Reviews = dict[tuple[str, int], str]  # (row id, run number) -> "correct" | "incorrect"


def is_correct(record: dict, row: EvalRow, reviews: Reviews) -> bool:
    """Script verdict (answered AND all key facts present), overridden by a hand review."""
    review = reviews.get((record["id"], record["run"]))
    if review is not None:
        return review == "correct"
    return record["outcome"] == "answered" and key_facts_present(record["answer"], row.key_facts)


def _first_answer_score(record: dict) -> float | None:
    for attempt in record.get("attempts", []):
        if attempt["kind"] == "answer" and attempt["nli_score"] is not None:
            return attempt["nli_score"]
    return None


def score_run(records: list[dict], rows: dict[str, EvalRow], reviews: Reviews) -> dict:
    """§11 metrics for ONE run (one pass over the split). Rates carry n = questions."""
    in_scope = [r for r in records if rows[r["id"]].in_scope]
    out_of_scope = [r for r in records if not rows[r["id"]].in_scope]
    decision_seeking = [r for r in records if rows[r["id"]].category == "decision_seeking"]

    before = [s for s in map(_first_answer_score, records) if s is not None]
    after = [
        r["groundedness_score"]
        for r in records
        if r["outcome"] == "answered" and r["groundedness_score"] is not None
    ]
    generated = [r for r in records if _first_answer_score(r) is not None]

    return {
        "answer_correctness": Rate(sum(is_correct(r, rows[r["id"]], reviews) for r in in_scope),
                                   len(in_scope)),
        "in_scope_abstained": Rate(sum(r["outcome"] == "abstained" for r in in_scope),
                                   len(in_scope)),
        "abstention_correctness": Rate(sum(r["outcome"] in ("refused", "abstained")
                                           for r in out_of_scope), len(out_of_scope)),
        "decision_seeking_refused_by_guardrail": Rate(
            sum(r["outcome"] == "refused" for r in decision_seeking), len(decision_seeking)),
        "groundedness_before_gate": mean(before) if before else None,
        "groundedness_after_gate": mean(after) if after else None,
        "n_answers_before_gate": len(before),
        "n_answers_after_gate": len(after),
        "generated_but_not_answered": Rate(
            sum(r["outcome"] != "answered" for r in generated), len(generated)),
    }


def summarize_repeats(per_run_values: list[float]) -> dict[str, float]:
    """Headline across repeats: the MEAN of the per-run values with the min-max range. Deliberately
    no pooled interval: repeats measure LLM variance, not sample size."""
    return {"mean": mean(per_run_values), "min": min(per_run_values), "max": max(per_run_values)}
