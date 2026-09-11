package com.claimspipeline.claimsintake;

import java.math.BigDecimal;

public record ClaimSubmittedEvent(
    String claimId,
    String employeeId,
    String planType,
    BigDecimal amountRequested,
    String description,
    String submittedAt) {}
