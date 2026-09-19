"""Read-only client for claims-intake-service's `GET /claims/{claimId}`.

This is the only way the assistant sees a claim (CLAUDE.md: never a direct `claims` collection
read), and it is deliberately GET-only: the assistant has no write path to claims, which is the
architectural backstop behind the guardrail.

Prompt-injection boundary: the response is parsed into `ClaimContext`, which holds ONLY
status, decisionReason, ruleTrace, planType and amountRequested. The submitter-controlled
free-text `description` (and every other field, e.g. employeeId) is dropped here, so it cannot
reach the LLM prompt, the NLI premises, or the API response.
"""

from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from typing import Protocol
from urllib.parse import quote

import httpx

from assistant.schemas import ClaimSummary


class ClaimNotFound(Exception):
    pass


class ClaimsServiceUnavailable(Exception):
    pass


@dataclass(frozen=True)
class ClaimContext:
    claim_id: str
    status: str | None = None
    plan_type: str | None = None
    amount_requested: Decimal | None = None
    decision_reason: str | None = None
    rule_trace: tuple[str, ...] = field(default_factory=tuple)

    def render(self) -> str:
        """Plain-text form used as an LLM context block and as an NLI/numeric-check premise."""
        parts = []
        if self.status:
            parts.append(f"status {self.status}")
        if self.plan_type:
            parts.append(f"plan type {self.plan_type}")
        if self.amount_requested is not None:
            parts.append(f"amount requested ${self.amount_requested:,.2f}")
        if self.decision_reason:
            parts.append(f"decision reason: {self.decision_reason}")
        if self.rule_trace:
            parts.append("rules applied: " + "; ".join(self.rule_trace))
        return "Claim record: " + "; ".join(parts) + "."

    def to_summary(self) -> ClaimSummary:
        return ClaimSummary(
            claimId=self.claim_id,
            status=self.status,
            planType=self.plan_type,
            amountRequested=(
                float(self.amount_requested) if self.amount_requested is not None else None
            ),
            decisionReason=self.decision_reason,
            ruleTrace=list(self.rule_trace),
        )


class ClaimsClient(Protocol):
    def get_claim(self, claim_id: str) -> ClaimContext: ...


def _parse_claim(claim_id: str, data: dict) -> ClaimContext:
    amount = data.get("amountRequested")
    try:
        amount_value = Decimal(str(amount)) if amount is not None else None
    except InvalidOperation:
        amount_value = None
    trace = data.get("ruleTrace") or []
    return ClaimContext(
        claim_id=claim_id,
        status=data.get("status"),
        plan_type=data.get("planType"),
        amount_requested=amount_value,
        decision_reason=data.get("decisionReason"),
        rule_trace=tuple(str(rule) for rule in trace),
    )


class HttpClaimsClient:
    def __init__(self, base_url: str, timeout_seconds: float = 5.0,
                 transport: httpx.BaseTransport | None = None) -> None:
        self._client = httpx.Client(
            base_url=base_url.rstrip("/"), timeout=timeout_seconds, transport=transport
        )

    def get_claim(self, claim_id: str) -> ClaimContext:
        try:
            response = self._client.get(f"/claims/{quote(claim_id, safe='')}")
        except httpx.HTTPError as exc:
            raise ClaimsServiceUnavailable(str(exc)) from exc
        if response.status_code == 404:
            raise ClaimNotFound(claim_id)
        if response.status_code != 200:
            raise ClaimsServiceUnavailable(f"claims-intake-service returned {response.status_code}")
        try:
            data = response.json()
        except ValueError as exc:
            raise ClaimsServiceUnavailable("claims-intake-service returned invalid JSON") from exc
        if not isinstance(data, dict):
            raise ClaimsServiceUnavailable("claims-intake-service returned unexpected JSON")
        return _parse_claim(claim_id, data)
