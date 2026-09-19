"""Real-model checks (marker `ml`; excluded from CI, run with `pytest -m ml`).

These use the actual NLI cross-encoder and embedding model against real policy-doc text, to
show what the fakes can't: whether the gate actually separates supported from unsupported
answers. Results are reported as-is — a miss here is a finding about NLI, not a test to tune away.
"""

import os
from pathlib import Path

import pytest

pytest.importorskip("sentence_transformers")

from assistant.groundedness import CrossEncoderNLI, GroundednessGate  # noqa: E402
from assistant.premises import Premise  # noqa: E402
from assistant.retrieval import SentenceTransformerEmbedder  # noqa: E402
from assistant.schemas import PolicyCitation  # noqa: E402

pytestmark = pytest.mark.ml

POLICY_DOCS = Path(__file__).resolve().parents[3] / "data" / "synthetic" / "policy_docs"
NLI_MODEL = os.environ.get("NLI_MODEL", "cross-encoder/nli-deberta-v3-small")
THRESHOLD = 0.5  # the provisional default; not calibrated (Phase 7b)


def section_text(plan: str, heading: str) -> str:
    text = (POLICY_DOCS / f"{plan}.md").read_text(encoding="utf-8")
    body = text.split(f"## {heading}", 1)[1]
    return body.split("\n## ", 1)[0].strip()


def premise(plan: str, heading: str) -> Premise:
    return Premise(
        label=f"policy: {plan} / {heading}",
        text=section_text(plan, heading),
        citation=PolicyCitation(docId=f"{plan}_x", planType=plan, section=heading, score=1.0),
    )


@pytest.fixture(scope="module")
def nli():
    return CrossEncoderNLI(NLI_MODEL)


@pytest.fixture(scope="module")
def gate(nli):
    return GroundednessGate(nli, THRESHOLD)


def test_supported_answer_scores_high_and_passes(gate):
    result = gate.evaluate(
        "The Group Dental Plan pays eligible claims up to a maximum of $2,000 per claim.",
        [premise("dental", "Coverage Limits")],
    )
    print(f"supported: nli={result.nli_score:.3f} numbers_supported={result.numbers_supported}")
    assert result.numbers_supported
    assert result.passed


def test_unsupported_claim_scores_low(gate):
    result = gate.evaluate(
        "The Group Dental Plan covers cosmetic surgery and pays for helicopter transport.",
        [premise("dental", "Coverage Limits")],
    )
    print(f"unsupported: nli={result.nli_score:.3f}")
    assert not result.passed


def test_fabricated_number_is_rejected(gate):
    """The known NLI weak spot. Whatever the NLI says, the numeric check must reject it."""
    result = gate.evaluate(
        "The Group Dental Plan pays eligible claims up to a maximum of $50,000 per claim.",
        [premise("dental", "Coverage Limits")],
    )
    print(
        f"fabricated number: nli={result.nli_score:.3f} "
        f"numbers_supported={result.numbers_supported} "
        f"(NLI alone {'WOULD have' if result.nli_score >= THRESHOLD else 'would NOT have'} passed)"
    )
    assert not result.numbers_supported
    assert not result.passed


def test_embedding_model_loads_and_returns_384_dims():
    vector = SentenceTransformerEmbedder().embed("What is the maximum benefit for a dental claim?")
    assert len(vector) == 384


# --- Pipeline invariants (added after the 2026-09-19 suicide-sentence investigation). ---
# That investigation found no pipeline bug: these pin what was verified so a future model swap
# or refactor can't silently break it. They do NOT assert that the model scores every faithful
# answer highly — it doesn't (see PLAN.md §12), which is a model limit, not a wiring bug.


def test_entailment_index_is_read_from_the_model_config(nli):
    labels = {int(i): str(name).lower() for i, name in nli._model.config.id2label.items()}
    assert labels[nli._entailment_index] == "entailment"
    print(f"id2label={labels} entailment_index={nli._entailment_index}")


def test_every_policy_chunk_fits_the_model_with_no_truncation(nli):
    tokenizer = nli._model.tokenizer
    limit = nli._model.config.max_position_embeddings
    hypothesis = "word " * 60  # generous: longer than any single answer sentence
    worst = 0
    for doc in sorted(POLICY_DOCS.glob("*.md")):
        for section in doc.read_text(encoding="utf-8").split("\n## ")[1:]:
            body = section.split("\n", 1)[1].strip()
            worst = max(worst, len(tokenizer(body, hypothesis, truncation=False)["input_ids"]))
    print(f"longest chunk+60-token hypothesis = {worst} tokens (limit {limit})")
    assert worst <= limit


def test_identical_text_is_scored_as_entailed(nli):
    sentence = (
        "Death resulting from suicide within the first 24 months of the enrollment's effective "
        "date is excluded from this plan's benefit."
    )
    assert nli.entailment([(sentence, sentence)])[0] > 0.9


def test_matches_raw_transformers_probabilities(nli):
    """The CrossEncoder wrapper must give the same probabilities as raw transformers."""
    torch = pytest.importorskip("torch")
    premise = section_text("life", "Exclusions")
    hypothesis = "Death resulting from participation in an illegal act is excluded."
    encoded = nli._model.tokenizer(premise, hypothesis, return_tensors="pt", truncation=True)
    with torch.no_grad():
        raw = torch.softmax(nli._model.model(**encoded).logits, -1)[0][nli._entailment_index]
    assert nli.entailment([(premise, hypothesis)])[0] == pytest.approx(float(raw), abs=1e-4)
