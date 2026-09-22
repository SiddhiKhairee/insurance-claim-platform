"""Recording wrappers that let the eval observe the REAL graph without changing it.

`GraphDeps` takes any object with `search` / `generate` / `evaluate`, so wrapping the store,
the providers and the gate captures every retrieved chunk, every raw provider answer and every
per-attempt gate result. The graph itself keeps discarding rejected answers; the recorders don't.

Single-threaded by design: one `Trace` is reset per question.
"""

from dataclasses import dataclass, field

from assistant.graph import GraphDeps
from assistant.groundedness import GateResult, GroundednessGate
from assistant.llm import INSUFFICIENT_CONTEXT, LLMProvider, ProviderError
from assistant.premises import Premise
from assistant.retrieval import Chunk, VectorStore
from evaluation.metrics import Attempt


@dataclass
class Trace:
    """What happened while answering one question."""

    attempts: list[Attempt] = field(default_factory=list)
    plan_type: str | None = None
    chunks: list[Chunk] = field(default_factory=list)
    searches: int = 0

    def reset(self) -> None:
        self.attempts = []
        self.plan_type = None
        self.chunks = []
        self.searches = 0

    def retrieval_dict(self) -> dict:
        return {
            "plan_type": self.plan_type,
            "chunks": [{"doc_id": c.doc_id, "score": c.score} for c in self.chunks],
        }


class RecordingStore:
    def __init__(self, inner: VectorStore, trace: Trace) -> None:
        self._inner = inner
        self._trace = trace

    def search(self, vector: list[float], k: int, plan_type: str | None) -> list[Chunk]:
        chunks = self._inner.search(vector, k, plan_type)
        self._trace.plan_type = plan_type
        self._trace.chunks = list(chunks)
        self._trace.searches += 1
        return chunks


class RecordingProvider:
    def __init__(self, inner: LLMProvider, trace: Trace) -> None:
        self._inner = inner
        self._trace = trace
        self.name = inner.name

    def generate(self, system: str, user: str) -> str:
        try:
            text = self._inner.generate(system, user)
        except ProviderError as exc:
            self._trace.attempts.append(Attempt(self.name, "error", error=str(exc)))
            raise
        if text.strip() == INSUFFICIENT_CONTEXT:
            self._trace.attempts.append(Attempt(self.name, "insufficient"))
        else:
            # Gate fields are filled in by RecordingGate.evaluate, which the graph calls next.
            self._trace.attempts.append(Attempt(self.name, "answer", answer=text))
        return text


class RecordingGate:
    """Delegates to the real gate and records its result on the attempt being evaluated.

    `always_reject=True` records the result but reports failure, so a single graph run walks the
    whole provider chain and captures every provider's attempt. Used only to COLLECT calibration
    data; scored runs use the real, unmodified pass/fail."""

    def __init__(
        self, inner: GroundednessGate, trace: Trace, *, always_reject: bool = False
    ) -> None:
        self._inner = inner
        self._trace = trace
        self._always_reject = always_reject

    def evaluate(self, answer: str, premises: list[Premise]) -> GateResult:
        result = self._inner.evaluate(answer, premises)
        last = self._trace.attempts[-1]
        self._trace.attempts[-1] = Attempt(
            provider=last.provider,
            kind="answer",
            answer=last.answer,
            nli_score=result.nli_score,
            numbers_supported=result.numbers_supported,
            unsupported_numbers=tuple(result.unsupported_numbers),
        )
        if self._always_reject:
            return GateResult(
                passed=False,
                nli_score=result.nli_score,
                numbers_supported=result.numbers_supported,
                unsupported_numbers=result.unsupported_numbers,
                citations=result.citations,
            )
        return result


def instrument(deps: GraphDeps, *, always_reject: bool = False) -> tuple[GraphDeps, Trace]:
    """Copy of `deps` with recorders around the store, providers and gate."""
    trace = Trace()
    wrapped = GraphDeps(
        embedder=deps.embedder,
        store=RecordingStore(deps.store, trace),
        claims=deps.claims,
        providers=[RecordingProvider(p, trace) for p in deps.providers],
        gate=RecordingGate(deps.gate, trace, always_reject=always_reject),
        top_k=deps.top_k,
    )
    return wrapped, trace
