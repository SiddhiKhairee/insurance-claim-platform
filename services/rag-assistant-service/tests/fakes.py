"""Test doubles for the assistant's Protocol-typed dependencies."""

from collections.abc import Callable
from decimal import Decimal

from assistant.claims_client import ClaimContext, ClaimNotFound, ClaimsServiceUnavailable
from assistant.graph import Assistant, GraphDeps
from assistant.groundedness import GroundednessGate
from assistant.llm import ProviderError
from assistant.retrieval import Chunk

DENTAL_LIMITS = Chunk(
    doc_id="dental_coverage-limits",
    plan_type="dental",
    section="Coverage Limits",
    text="The Group Dental Plan pays eligible claims up to a maximum of $2,000.00 per claim.",
    score=0.85,
)
DENTAL_EXCLUSIONS = Chunk(
    doc_id="dental_exclusions",
    plan_type="dental",
    section="Exclusions",
    text="The Group Dental Plan excludes cosmetic procedures such as teeth whitening.",
    score=0.79,
)
CORRECT_ANSWER = "The Group Dental Plan pays eligible claims up to a maximum of $2,000 per claim."
HALLUCINATED_ANSWER = (
    "The Group Dental Plan pays eligible claims up to a maximum of $50,000 per claim."
)

DENIED_CLAIM = ClaimContext(
    claim_id="claim-1",
    status="DENIED",
    plan_type="dental",
    amount_requested=Decimal("2500.00"),
    decision_reason="amount exceeds plan limit",
    rule_trace=("coverage check: passed", "plan-limit check: failed"),
)


class FakeEmbedder:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def embed(self, text: str) -> list[float]:
        self.calls.append(text)
        return [0.1, 0.2, 0.3]


class FakeStore:
    def __init__(self, chunks: list[Chunk] | None = None, error: Exception | None = None) -> None:
        self.chunks = [DENTAL_LIMITS, DENTAL_EXCLUSIONS] if chunks is None else chunks
        self.error = error
        self.calls: list[tuple[int, str | None]] = []

    def search(self, vector: list[float], k: int, plan_type: str | None) -> list[Chunk]:
        self.calls.append((k, plan_type))
        if self.error:
            raise self.error
        return self.chunks[:k]


class FakeClaims:
    def __init__(self, claim: ClaimContext | None = None, error: Exception | None = None) -> None:
        self.claim = claim
        self.error = error
        self.calls: list[str] = []

    def get_claim(self, claim_id: str) -> ClaimContext:
        self.calls.append(claim_id)
        if self.error:
            raise self.error
        if self.claim is None:
            raise ClaimNotFound(claim_id)
        return self.claim


class FakeProvider:
    """Returns scripted responses in order; an Exception in the script is raised as a
    ProviderError. Records every (system, user) prompt it receives."""

    def __init__(self, name: str, responses: list[str | Exception]) -> None:
        self.name = name
        self.responses = list(responses)
        self.prompts: list[tuple[str, str]] = []

    def generate(self, system: str, user: str) -> str:
        self.prompts.append((system, user))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise ProviderError(str(response))
        return response


class FakeNLI:
    """Scores every pair with `score`, or with `score_fn(premise, hypothesis)`."""

    def __init__(self, score: float = 0.99,
                 score_fn: Callable[[str, str], float] | None = None) -> None:
        self.score = score
        self.score_fn = score_fn
        self.pairs: list[tuple[str, str]] = []

    def entailment(self, pairs: list[tuple[str, str]]) -> list[float]:
        self.pairs.extend(pairs)
        if self.score_fn:
            return [self.score_fn(premise, hypothesis) for premise, hypothesis in pairs]
        return [self.score] * len(pairs)


def make_assistant(
    *,
    providers: list[FakeProvider],
    nli: FakeNLI | None = None,
    store: FakeStore | None = None,
    claims: FakeClaims | None = None,
    embedder: FakeEmbedder | None = None,
    threshold: float = 0.5,
    top_k: int = 3,
) -> tuple[Assistant, GraphDeps]:
    deps = GraphDeps(
        embedder=embedder or FakeEmbedder(),
        store=store or FakeStore(),
        claims=claims or FakeClaims(),
        providers=providers,
        gate=GroundednessGate(nli or FakeNLI(), threshold),
        top_k=top_k,
    )
    return Assistant(deps), deps


__all__ = [
    "ClaimsServiceUnavailable",
    "CORRECT_ANSWER",
    "DENIED_CLAIM",
    "DENTAL_EXCLUSIONS",
    "DENTAL_LIMITS",
    "FakeClaims",
    "FakeEmbedder",
    "FakeNLI",
    "FakeProvider",
    "FakeStore",
    "HALLUCINATED_ANSWER",
    "make_assistant",
]
