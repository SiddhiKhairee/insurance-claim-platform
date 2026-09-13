package com.claimspipeline.notification;

import java.util.List;

public record ClaimAdjudicatedEvent(
    String claimId,
    String status,
    String decisionReason,
    List<String> ruleTrace,
    String adjudicatedAt) {}
