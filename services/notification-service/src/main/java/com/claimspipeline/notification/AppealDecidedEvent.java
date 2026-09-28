package com.claimspipeline.notification;

/**
 * Payload of {@code claim.appeal-decided}, published by claims-intake-service when an admin
 * upholds or overturns a denied claim on appeal (PLAN.md §4). {@code outcome} is
 * "upheld" or "overturned".
 */
public record AppealDecidedEvent(
    String claimId, String outcome, String reviewerNote, String decidedAt) {}
