package com.claimspipeline.claimsintake;

/**
 * Payload of {@code claim.appeal-decided} (PLAN.md §4). {@code outcome} is lowercase
 * ("upheld"/"overturned"), matching {@code claim.adjudicated}'s "approved"/"denied"; Mongo keeps
 * the uppercase enum, as it does for claim statuses.
 */
public record AppealDecidedEvent(
    String claimId, String outcome, String reviewerNote, String decidedAt) {}
