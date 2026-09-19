"""Deterministic guardrail: refuses requests to make an adjudication decision.

No LLM, and the decision is made from the question text alone (no network calls). Adjudication
is rule-based (CLAUDE.md); this assistant explains decisions and answers policy questions only.

Best-effort by design: regex rules cannot cover every phrasing. The real enforcement is
architectural — this service has no write path to claims and never publishes to Kafka, so even
a phrasing that slips through cannot change a decision.

Explanation/information questions ("why was my claim denied?", "what would cause a claim to be
denied?") are allowed. A leading wh-word/"explain" exempts the modal and status-change rules,
but not direct imperatives ("approve my claim") or explicit decision requests ("decide whether").
"""

import re
from dataclasses import dataclass

_VERB = (
    r"(?:approve|deny|reject|decline|overturn|override|overrule|reverse|adjudicate|"
    r"authorize|authorise|pay out|payout|settle)"
)
_OBJECT = (
    r"(?:claim|claims|it|this|that|them|request|payment|payout|benefit|coverage|"
    r"denial|decision|determination|ruling|rule engine)"
)
_PASSIVE = r"(?:approved|denied|rejected|declined|paid|paid out|overturned|reversed)"
_SUBJECT = r"(?:my|our|this|that|the|it)"

_EXPLANATION_START = re.compile(
    r"^(?:why|what|how|when|which|where|who|explain|describe|tell me (?:why|what|how|when))\b"
)

# Rules that apply even to wh-/explain questions.
_ALWAYS = [
    # Imperative or polite request at the start: "approve my claim", "please deny this".
    re.compile(
        rf"^(?:(?:please|pls|kindly|go ahead and)\s+)*"
        rf"(?:(?:can|could|would|will) you\s+(?:please\s+)?)?{_VERB}\b"
        rf"(?:\W+\w+){{0,3}}?\W+{_OBJECT}\b"
    ),
    re.compile(rf"^(?:please\s+)?{_VERB}\W*$"),
    re.compile(r"\bdecide\s+(?:whether|if)\b"),
    re.compile(r"\bmake\s+(?:an?|the)\s+(?:decision|determination|exception|ruling)\b"),
    re.compile(
        rf"\b(?:want|need|require|expect|ask)\w*\s+(?:you|the (?:system|assistant|ai|bot))"
        rf"\s+to\s+{_VERB}\b"
    ),
    re.compile(r"\b(?:give|grant)\s+(?:me|us)\s+(?:an?\s+)?(?:approval|payout|payment)\b"),
]

# Rules exempted for wh-/explain questions (which are information requests).
_UNLESS_EXPLANATION = [
    # Modal request to act: "can you approve my claim", "should we deny this".
    re.compile(
        rf"\b(?:should|shall|must|can|could|would|will|may)\s+"
        rf"(?:we|i|you|they|the\s+\w+|this|it)\s+(?:\w+\s+){{0,2}}?{_VERB}\b"
        rf"(?:\W+\w+){{0,3}}?\W+{_OBJECT}\b"
    ),
    # Outcome prediction about a specific claim: "will my claim be approved".
    re.compile(
        rf"\b(?:will|would|should|can|could|must)\s+{_SUBJECT}\b(?:\s+\w+){{0,3}}?"
        rf"\s+be\s+{_PASSIVE}\b"
    ),
    re.compile(rf"\b(?:going to|gonna)\s+be\s+{_PASSIVE}\b"),
    # Status-change requests: "mark my claim as approved", "change the status to paid".
    re.compile(
        r"\b(?:mark|set|change|update|flip|switch)\b.{0,40}"
        r"\b(?:approved|denied|rejected|paid|decision|status)\b"
    ),
]


@dataclass(frozen=True)
class GuardrailDecision:
    refuse: bool
    matched_rule: str | None = None


def _normalize(question: str) -> str:
    text = question.lower().replace("’", "'").replace("‘", "'")
    return re.sub(r"\s+", " ", text).strip()


def check_question(question: str) -> GuardrailDecision:
    text = _normalize(question)
    for rule in _ALWAYS:
        if rule.search(text):
            return GuardrailDecision(True, rule.pattern)
    if _EXPLANATION_START.match(text):
        return GuardrailDecision(False)
    for rule in _UNLESS_EXPLANATION:
        if rule.search(text):
            return GuardrailDecision(True, rule.pattern)
    return GuardrailDecision(False)
