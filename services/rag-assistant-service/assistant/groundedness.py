"""Groundedness gate: no generated text is returned unless it passes BOTH checks below.

1. NLI check. A local NLI cross-encoder scores each answer sentence against each premise
   (retrieved policy chunk or the rendered claim record) one pair at a time, because chunks are
   ~300-450 tokens and the whole context cannot fit one 512-token pass. A sentence's score is the
   max entailment probability over premises; the answer's score is the MIN over sentences, so one
   unsupported sentence fails the whole answer. Below the threshold -> fail. The threshold (0.5)
   is NOT calibrated: Phase 7b's calibration found no threshold that separates faithful from
   fabricated answers on this corpus, so the provisional value was kept (PLAN.md section 12,
   2026-09-21).

2. Deterministic numeric check (extension to the 2026-09-19 NLI-only gate description). Every
   number / dollar amount / percentage in the answer, normalized so "$5,000", "5000" and
   "5,000.00" match, must appear in at least one premise. Any unsupported number -> fail,
   regardless of the NLI score. List markers ("1."), labelled references ("Section 3") and
   ordinals ("1st") are not treated as numbers. This check is ANSWER-LEVEL: a number only has to
   appear in SOME premise, not in the premise supporting the sentence it is in. Pairing each
   number with the right sentence is left to the NLI check.

Known limits, stated wherever this metric is reported:
- NLI is weak on NUMBERS (it can rate "the limit is $50,000" as entailed by a passage saying
  $2,000) — the numeric check is the backstop, but the NLI score alone must not be trusted on them.
- NLI is weak on NEGATION ("is excluded" vs "is not excluded" can score alike).
- CROSS-SENTENCE PRONOUN REFERENCES are lost because each sentence is scored alone: "It is
  excluded during the first two years" has no referent. The generation prompt therefore requires
  self-contained sentences.
- Spelled-out numbers ("two years") are not covered by the numeric check.
"""

import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from typing import Protocol

from assistant.premises import Premise
from assistant.schemas import Citation

# Digits only; not glued to letters (so "1st", "2nd", UUID fragments are skipped). Possessive
# quantifiers (Python 3.11+) stop the regex backtracking into a partial match of "12,345abc".
_NUMBER = re.compile(r"(?<![A-Za-z0-9])\$?\d[\d,]*+(?:\.\d+)?+(?![A-Za-z0-9])")
_LIST_MARKER = re.compile(r"^[ \t]*(?:[-*•]|\(?\d+[.)])[ \t]+", re.MULTILINE)
_LABELLED_NUMBER = re.compile(
    r"\b(?:section|sec\.?|article|step|part|item|clause|paragraph|rule|table|figure|page)"
    r"\s+\d+(?:\.\d+)*",
    re.IGNORECASE,
)
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z\"'(\[])|\n+")


class NLIScorer(Protocol):
    def entailment(self, pairs: list[tuple[str, str]]) -> list[float]:
        """Entailment probability in [0, 1] for each (premise, hypothesis) pair."""
        ...


class CrossEncoderNLI:
    def __init__(self, model_name: str) -> None:
        from sentence_transformers import CrossEncoder  # lazy: keeps CI free of torch

        self._model = CrossEncoder(model_name)
        labels = {int(i): str(label).lower() for i, label in self._model.config.id2label.items()}
        try:
            self._entailment_index = next(i for i, label in labels.items() if "entail" in label)
        except StopIteration as exc:
            raise ValueError(f"{model_name} has no entailment label: {labels}") from exc

    def entailment(self, pairs: list[tuple[str, str]]) -> list[float]:
        if not pairs:
            return []
        probabilities = self._model.predict(pairs, apply_softmax=True)
        return [float(row[self._entailment_index]) for row in probabilities]


def extract_numbers(text: str, *, ignore_labels: bool = False) -> list[Decimal]:
    """Normalized numeric values in `text` (commas and `$` stripped)."""
    if ignore_labels:
        text = _LIST_MARKER.sub("", text)
        text = _LABELLED_NUMBER.sub("", text)
    values = []
    for match in _NUMBER.finditer(text):
        try:
            values.append(Decimal(match.group().lstrip("$").replace(",", "")))
        except InvalidOperation:
            continue
    return values


def split_sentences(text: str) -> list[str]:
    # Markers are stripped first: otherwise the "." in "1." looks like a sentence end.
    text = _LIST_MARKER.sub("", text.strip())
    return [part.strip() for part in _SENTENCE_SPLIT.split(text) if part.strip()]


@dataclass(frozen=True)
class GateResult:
    passed: bool
    nli_score: float | None
    numbers_supported: bool
    unsupported_numbers: list[str] = field(default_factory=list)
    citations: list[Citation] = field(default_factory=list)


class GroundednessGate:
    def __init__(self, scorer: NLIScorer, threshold: float) -> None:
        self._scorer = scorer
        self._threshold = threshold

    def evaluate(self, answer: str, premises: list[Premise]) -> GateResult:
        sentences = split_sentences(answer)
        if not sentences or not premises:
            return GateResult(passed=False, nli_score=None, numbers_supported=False)

        # Numeric check (answer-level).
        premise_numbers = {n for premise in premises for n in extract_numbers(premise.text)}
        unsupported = [
            format(n.normalize(), "f")
            for n in extract_numbers(answer, ignore_labels=True)
            if n not in premise_numbers
        ]
        numbers_supported = not unsupported

        # NLI check: every sentence against every premise, one batched call.
        pairs = [(premise.text, sentence) for sentence in sentences for premise in premises]
        scores = self._scorer.entailment(pairs)
        sentence_scores = []
        citations: list[Citation] = []
        width = len(premises)
        for row in range(len(sentences)):
            row_scores = scores[row * width : (row + 1) * width]
            best = max(range(width), key=lambda i: row_scores[i])
            sentence_scores.append(row_scores[best])
            citation = premises[best].citation
            if citation not in citations:
                citations.append(citation)
        nli_score = min(sentence_scores)

        return GateResult(
            passed=numbers_supported and nli_score >= self._threshold,
            nli_score=nli_score,
            numbers_supported=numbers_supported,
            unsupported_numbers=unsupported,
            citations=citations,
        )
