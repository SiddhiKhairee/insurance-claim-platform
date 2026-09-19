"""The assistant's LangGraph: guardrail -> retrieve -> generate -> groundedness.

    guardrail --refuse--> refuse --> END
        |
        +--allow--> retrieve --> generate --> groundedness --pass--> END
                        |           ^   |          |
                        |           |   +--error---+--fail--> next provider (or abstain)
                        +--claim/retrieval problem--> END (abstained)

Ordering rules:
- The guardrail decides from the question text alone, before retrieval or any network call.
- On refusal, the `refuse` node may then fetch the claim to quote its decision and ruleTrace,
  but a refusal never depends on that fetch: any failure falls back to the generic wording.
- No generated text is returned unless it passed the groundedness gate. Provider errors and gate
  failures both advance the provider chain; when it is exhausted the answer is a deterministic
  abstention.
"""

import logging
from collections.abc import Sequence
from dataclasses import dataclass
from typing import TypedDict

from langgraph.graph import END, StateGraph

from assistant.claims_client import (
    ClaimContext,
    ClaimNotFound,
    ClaimsClient,
    ClaimsServiceUnavailable,
)
from assistant.groundedness import GateResult, GroundednessGate
from assistant.guardrail import check_question
from assistant.llm import (
    INSUFFICIENT_CONTEXT,
    SYSTEM_PROMPT,
    LLMProvider,
    ProviderError,
    build_user_prompt,
)
from assistant.premises import Premise, premise_from_chunk, premise_from_claim
from assistant.retrieval import PLAN_TYPES, Chunk, Embedder, VectorStore, infer_plan_type
from assistant.schemas import AskResponse

logger = logging.getLogger(__name__)

ABSTAIN_MESSAGE = "I don't have enough information to answer that reliably."
CLAIM_NOT_FOUND_MESSAGE = "I couldn't find that claim."
CLAIMS_UNAVAILABLE_MESSAGE = (
    "I couldn't retrieve that claim right now, so I can't answer questions about it reliably."
)
REFUSAL_GENERIC = (
    "I don't make adjudication decisions. Claims are approved or denied only by the rule "
    "engine; see the claim's ruleTrace for the rules that were applied."
)


@dataclass
class GraphDeps:
    embedder: Embedder
    store: VectorStore
    claims: ClaimsClient
    providers: Sequence[LLMProvider]
    gate: GroundednessGate
    top_k: int = 3


class AssistantState(TypedDict, total=False):
    question: str
    claim_id: str | None
    refused: bool
    claim: ClaimContext | None
    premises: list[Premise]
    provider_index: int
    answer: str | None
    provider: str | None
    gate: GateResult | None
    outcome: str | None
    reason: str | None
    message: str | None


def _refusal_with_claim(claim: ClaimContext) -> str:
    parts = ["I don't make adjudication decisions; the rule engine does."]
    recorded = []
    if claim.status:
        recorded.append(f"status {claim.status}")
    if claim.decision_reason:
        recorded.append(f"decision reason: {claim.decision_reason}")
    if claim.rule_trace:
        recorded.append("ruleTrace: " + "; ".join(claim.rule_trace))
    if recorded:
        parts.append("For this claim the rule engine recorded " + "; ".join(recorded) + ".")
    return " ".join(parts)


def build_graph(deps: GraphDeps):
    def guardrail(state: AssistantState) -> AssistantState:
        decision = check_question(state["question"])
        if decision.refuse:
            logger.info("guardrail refused question (rule=%s)", decision.matched_rule)
        return {"refused": decision.refuse}

    def refuse(state: AssistantState) -> AssistantState:
        claim = None
        if state.get("claim_id"):
            try:
                claim = deps.claims.get_claim(state["claim_id"])
            except (ClaimNotFound, ClaimsServiceUnavailable) as exc:
                logger.info("refusal without claim details (%s)", exc.__class__.__name__)
        message = _refusal_with_claim(claim) if claim else REFUSAL_GENERIC
        return {
            "outcome": "refused",
            "reason": "decision_request",
            "message": message,
            "claim": claim,
        }

    def retrieve(state: AssistantState) -> AssistantState:
        question = state["question"]
        claim = None
        if state.get("claim_id"):
            try:
                claim = deps.claims.get_claim(state["claim_id"])
            except ClaimNotFound:
                return {
                    "outcome": "abstained",
                    "reason": "claim_not_found",
                    "message": CLAIM_NOT_FOUND_MESSAGE,
                }
            except ClaimsServiceUnavailable as exc:
                logger.warning("claims-intake-service unavailable: %s", exc)
                return {
                    "outcome": "abstained",
                    "reason": "claims_service_unavailable",
                    "message": CLAIMS_UNAVAILABLE_MESSAGE,
                }

        plan_type = None
        if claim and claim.plan_type in PLAN_TYPES:
            plan_type = claim.plan_type
        if plan_type is None:
            plan_type = infer_plan_type(question)

        try:
            chunks: list[Chunk] = deps.store.search(
                deps.embedder.embed(question), deps.top_k, plan_type
            )
        except Exception:
            logger.exception("retrieval failed")
            return {
                "outcome": "abstained",
                "reason": "retrieval_error",
                "message": ABSTAIN_MESSAGE,
                "claim": claim,
            }

        premises = [premise_from_chunk(chunk) for chunk in chunks]
        if claim:
            premises.append(premise_from_claim(claim))
        if not chunks:
            return {
                "outcome": "abstained",
                "reason": "no_relevant_context",
                "message": ABSTAIN_MESSAGE,
                "claim": claim,
            }
        return {"claim": claim, "premises": premises, "provider_index": 0}

    def generate(state: AssistantState) -> AssistantState:
        index = state["provider_index"]
        provider = deps.providers[index]
        prompt = build_user_prompt(state["question"], state["premises"])
        try:
            text = provider.generate(SYSTEM_PROMPT, prompt)
        except ProviderError as exc:
            logger.warning("provider %s failed: %s", provider.name, exc)
            return {"answer": None, "provider_index": index + 1}
        if text.strip() == INSUFFICIENT_CONTEXT:
            return {
                "outcome": "abstained",
                "reason": "insufficient_context",
                "message": ABSTAIN_MESSAGE,
                "provider": provider.name,
            }
        return {"answer": text, "provider": provider.name}

    def groundedness(state: AssistantState) -> AssistantState:
        result = deps.gate.evaluate(state["answer"], state["premises"])
        if result.passed:
            return {"gate": result, "outcome": "answered"}
        logger.info(
            "groundedness gate rejected %s answer (nli=%s, unsupported_numbers=%s)",
            state["provider"],
            result.nli_score,
            result.unsupported_numbers,
        )
        return {
            "gate": result,
            "answer": None,
            "provider": None,
            "provider_index": state["provider_index"] + 1,
        }

    def abstain(state: AssistantState) -> AssistantState:
        return {
            "outcome": "abstained",
            "reason": "no_grounded_answer",
            "message": ABSTAIN_MESSAGE,
            "provider": None,
        }

    def after_guardrail(state: AssistantState) -> str:
        return "refuse" if state["refused"] else "retrieve"

    def after_retrieve(state: AssistantState) -> str:
        if state.get("outcome"):
            return END
        return "generate" if deps.providers else "abstain"

    def next_step(state: AssistantState) -> str:
        """Shared routing after `generate` / `groundedness`."""
        if state.get("outcome"):
            return END
        if state.get("answer"):
            return "groundedness"
        return "generate" if state["provider_index"] < len(deps.providers) else "abstain"

    graph = StateGraph(AssistantState)
    graph.add_node("guardrail", guardrail)
    graph.add_node("refuse", refuse)
    graph.add_node("retrieve", retrieve)
    graph.add_node("generate", generate)
    graph.add_node("groundedness", groundedness)
    graph.add_node("abstain", abstain)
    graph.set_entry_point("guardrail")
    graph.add_conditional_edges("guardrail", after_guardrail, ["refuse", "retrieve"])
    graph.add_edge("refuse", END)
    graph.add_conditional_edges("retrieve", after_retrieve, ["generate", "abstain", END])
    graph.add_conditional_edges("generate", next_step, ["groundedness", "generate", "abstain", END])
    graph.add_conditional_edges("groundedness", next_step, ["generate", "abstain", END])
    graph.add_edge("abstain", END)
    return graph.compile()


class Assistant:
    def __init__(self, deps: GraphDeps) -> None:
        self._graph = build_graph(deps)

    def ask(self, question: str, claim_id: str | None = None) -> AskResponse:
        state = self._graph.invoke(
            {"question": question, "claim_id": claim_id, "provider_index": 0}
        )
        claim = state.get("claim")
        gate: GateResult | None = state.get("gate")
        outcome = state["outcome"]
        return AskResponse(
            outcome=outcome,
            answer=state["answer"] if outcome == "answered" else state["message"],
            reason=state.get("reason"),
            provider=state.get("provider") if outcome != "refused" else None,
            groundedness_score=gate.nli_score if gate else None,
            numbers_supported=gate.numbers_supported if gate else None,
            citations=gate.citations if gate and outcome == "answered" else [],
            claim=claim.to_summary() if claim else None,
        )
