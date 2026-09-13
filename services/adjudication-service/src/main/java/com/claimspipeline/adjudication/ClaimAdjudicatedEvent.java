package com.claimspipeline.adjudication;

import java.util.List;

/**
 * {@code status} is lowercase ("approved"/"denied") to match PLAN.md §4's exact event
 * schema — this intentionally differs from the uppercase {@code Claim.status} value
 * ("APPROVED"/"DENIED") stored in Mongo, which instead matches claims-intake's
 * "SUBMITTED" convention for that field.
 */
public record ClaimAdjudicatedEvent(
    String claimId,
    String status,
    String decisionReason,
    List<String> ruleTrace,
    String adjudicatedAt) {}
