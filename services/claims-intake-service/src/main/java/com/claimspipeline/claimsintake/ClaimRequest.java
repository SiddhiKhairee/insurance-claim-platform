package com.claimspipeline.claimsintake;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ClaimRequest(
    @NotBlank String employeeId,
    @NotBlank @Pattern(regexp = "disability|dental|vision|life") String planType,
    @NotNull @Positive BigDecimal amountRequested,
    String description) {}
