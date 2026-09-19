"""API request/response models for `POST /assistant/ask`.

Response contract:
  outcome             "answered" | "refused" | "abstained"
  answer              user-facing text. For "answered" it is the LLM answer that passed the
                      groundedness gate; for "refused"/"abstained" it is deterministic text,
                      never generated text.
  reason              null when answered; otherwise a machine-readable code
                      (decision_request, no_grounded_answer, insufficient_context,
                      no_relevant_context, claim_not_found, claims_service_unavailable,
                      retrieval_error)
  provider            which LLM produced the answer ("groq" | "gemini"); null when the
                      guardrail refused or no provider produced an accepted answer
  groundedness_score  NLI score (min over answer sentences of the best entailment against any
                      premise). PROVISIONAL threshold until Phase 7b calibration. On an
                      abstention this is the score of the last rejected attempt, if any.
  numbers_supported   whether every number in the answer appears in some premise
  citations           the premises the gate found supporting the answer (gate-derived, not
                      LLM-claimed). Tagged union:
                        {source: "policy", docId, planType, section, score}
                        {source: "claim", claimId}
  claim               narrow claim summary (only status/planType/amountRequested/
                      decisionReason/ruleTrace) when a claimId was supplied and readable
"""

from typing import Annotated, Literal

from pydantic import BaseModel, Field


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    # Constrained because it is interpolated into a REST path on claims-intake-service.
    claimId: str | None = Field(default=None, pattern=r"^[A-Za-z0-9-]{1,64}$")


class PolicyCitation(BaseModel):
    source: Literal["policy"] = "policy"
    docId: str
    planType: str
    section: str
    score: float


class ClaimCitation(BaseModel):
    source: Literal["claim"] = "claim"
    claimId: str


Citation = Annotated[PolicyCitation | ClaimCitation, Field(discriminator="source")]


class ClaimSummary(BaseModel):
    claimId: str
    status: str | None = None
    planType: str | None = None
    amountRequested: float | None = None
    decisionReason: str | None = None
    ruleTrace: list[str] = Field(default_factory=list)


class AskResponse(BaseModel):
    outcome: Literal["answered", "refused", "abstained"]
    answer: str
    reason: str | None = None
    provider: str | None = None
    groundedness_score: float | None = None
    numbers_supported: bool | None = None
    citations: list[Citation] = Field(default_factory=list)
    claim: ClaimSummary | None = None
