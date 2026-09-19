"""A Premise is one piece of trusted context: a retrieved policy chunk or the rendered claim
record. Premises feed both the LLM prompt and the groundedness gate, and carry the citation
that is reported if the gate finds a sentence supported by them."""

from dataclasses import dataclass

from assistant.claims_client import ClaimContext
from assistant.retrieval import Chunk
from assistant.schemas import ClaimCitation, PolicyCitation


@dataclass(frozen=True)
class Premise:
    label: str
    text: str
    citation: PolicyCitation | ClaimCitation


def premise_from_chunk(chunk: Chunk) -> Premise:
    return Premise(
        label=f"policy: {chunk.plan_type} / {chunk.section}",
        text=chunk.text,
        citation=PolicyCitation(
            docId=chunk.doc_id,
            planType=chunk.plan_type,
            section=chunk.section,
            score=chunk.score,
        ),
    )


def premise_from_claim(claim: ClaimContext) -> Premise:
    return Premise(
        label="claim record",
        text=claim.render(),
        citation=ClaimCitation(claimId=claim.claim_id),
    )
