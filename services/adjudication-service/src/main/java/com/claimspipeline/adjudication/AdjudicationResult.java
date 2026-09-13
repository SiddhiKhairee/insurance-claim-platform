package com.claimspipeline.adjudication;

import java.util.List;

public record AdjudicationResult(boolean approved, String decisionReason, List<String> ruleTrace) {}
