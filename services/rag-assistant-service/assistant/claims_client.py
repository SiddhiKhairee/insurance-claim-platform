"""Read-only client for claims-intake-service's `GET /claims/{claimId}`.

This is the only way the assistant sees a claim (CLAUDE.md: never a direct `claims` collection
read), and it is deliberately GET-only: the assistant has no write path to claims, which is the
architectural backstop behind the guardrail.

Prompt-injection boundary: the response is parsed into `ClaimContext`, which holds ONLY
status, decisionReason, ruleTrace, planType and amountRequested. The submitter-controlled
free-text `description` (and every other field, e.g. employeeId) is dropped here, so it cannot
reach the LLM prompt, the NLI premises, or the API response.

Appeals (Phase 8b): only the appeal's status (from a fixed set) and its submitted/decided dates
are kept, plus `displayStatus`. Deliberately dropped (owner decision, PLAN.md §12 2026-09-28):
- `appeal.reviewerNote`: a person's free text. It is an injection surface, and an LLM
  paraphrase could misstate a human's reasoning. The claimant reads it on the claim page.
- `appeal.documentCount` and anything about documents: the assistant has no access to supporting
  documents or references to them (PLAN.md §12, Phase 8b scope entry).
The assistant may REPORT a recorded appeal outcome; it never predicts one (see guardrail.py).
"""

from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Protocol
from urllib.parse import quote

import httpx

from assistant.schemas import AppealSummary, ClaimSummary


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
    display_status: str | None = None
    appeal_status: str | None = None
    appeal_submitted_on: str | None = None
    appeal_decided_on: str | None = None

    def render(self) -> str:
        """Plain-text form used as an LLM context block and as an NLI/numeric-check premise.

        The rule engine's decision comes first; a recorded appeal outcome (a person's decision)
        follows it, so the explanation keeps the two apart."""
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
        text = "Claim record: " + "; ".join(parts) + "."
        appeal = self.appeal_sentence()
        return f"{text} {appeal}" if appeal else text

    def appeal_sentence(self) -> str | None:
        """The recorded appeal state in fixed wording, or None when there is no appeal."""
        if self.appeal_status == "PENDING_REVIEW":
            submitted = f" on {self.appeal_submitted_on}" if self.appeal_submitted_on else ""
            return (
                f"Appeal record: the claimant appealed the rule engine's decision{submitted}; "
                "the appeal is pending human review and no appeal outcome has been recorded."
            )
        if self.appeal_status in ("UPHELD", "OVERTURNED"):
            decided = f" on {self.appeal_decided_on}" if self.appeal_decided_on else ""
            if self.appeal_status == "UPHELD":
                outcome = "upheld the rule engine's decision"
            else:
                outcome = "overturned the rule engine's decision and approved the claim"
            current = (
                f"; the claim's current status is {self.display_status}"
                if self.display_status
                else ""
            )
            return (
                f"Appeal record: a human reviewer {outcome} on appeal{decided}{current}. "
                "The rule engine's original decision is unchanged in the record."
            )
        return None

    def to_summary(self) -> ClaimSummary:
        return ClaimSummary(
            claimId=self.claim_id,
            status=self.status,
            displayStatus=self.display_status,
            planType=self.plan_type,
            amountRequested=(
                float(self.amount_requested) if self.amount_requested is not None else None
            ),
            decisionReason=self.decision_reason,
            ruleTrace=list(self.rule_trace),
            appeal=(
                AppealSummary(
                    status=self.appeal_status,
                    submittedOn=self.appeal_submitted_on,
                    decidedOn=self.appeal_decided_on,
                )
                if self.appeal_status
                else None
            ),
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
    appeal = data.get("appeal") if isinstance(data.get("appeal"), dict) else {}
    appeal_status = appeal.get("status")
    display_status = data.get("displayStatus")
    return ClaimContext(
        claim_id=claim_id,
        status=data.get("status"),
        plan_type=data.get("planType"),
        amount_requested=amount_value,
        decision_reason=data.get("decisionReason"),
        rule_trace=tuple(str(rule) for rule in trace),
        display_status=display_status if display_status in _DISPLAY_STATUSES else None,
        appeal_status=appeal_status if appeal_status in _APPEAL_STATUSES else None,
        appeal_submitted_on=_date_only(appeal.get("submittedAt")),
        appeal_decided_on=_date_only(appeal.get("decidedAt")),
    )


# Fixed vocabularies: only these values are copied into the prompt, so a field can't carry text.
_APPEAL_STATUSES = frozenset({"PENDING_REVIEW", "UPHELD", "OVERTURNED"})
_DISPLAY_STATUSES = frozenset({"SUBMITTED", "APPROVED", "DENIED", "APPROVED_ON_APPEAL"})


def _date_only(value: object) -> str | None:
    """ISO-8601 instant -> YYYY-MM-DD, or None if it isn't one."""
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).date().isoformat()
    except ValueError:
        return None


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
