package com.claimspipeline.adjudication;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deterministic, rule-based adjudication — never LLM-driven. Runs three fixed checks
 * (coverage, plan limit, duplicate) and always runs all three so {@code ruleTrace} shows
 * the full picture, rather than short-circuiting on the first failure. A pluggable
 * Rule-interface/registry was considered and rejected: three fixed checks with no plan
 * to add more dynamically don't earn that abstraction.
 */
@Component
public class RuleEngine {

  private final EnrollmentClient enrollmentClient;
  private final ClaimRepository claimRepository;
  private final Map<String, BigDecimal> planLimits;

  public RuleEngine(
      EnrollmentClient enrollmentClient,
      ClaimRepository claimRepository,
      Map<String, BigDecimal> planLimits) {
    this.enrollmentClient = enrollmentClient;
    this.claimRepository = claimRepository;
    this.planLimits = planLimits;
  }

  public AdjudicationResult evaluate(Claim claim) {
    List<String> ruleTrace = new ArrayList<>();
    boolean coveragePassed = checkCoverage(claim, ruleTrace);
    boolean planLimitPassed = checkPlanLimit(claim, ruleTrace);
    boolean duplicatePassed = checkDuplicate(claim, ruleTrace);

    boolean approved = coveragePassed && planLimitPassed && duplicatePassed;
    String decisionReason =
        approved
            ? "Approved: active coverage found, within plan limit, no duplicate found."
            : "Denied: " + failureSummary(coveragePassed, planLimitPassed, duplicatePassed);
    return new AdjudicationResult(approved, decisionReason, ruleTrace);
  }

  private boolean checkCoverage(Claim claim, List<String> ruleTrace) {
    LocalDate claimDate = claim.getSubmittedAt().atZone(ZoneOffset.UTC).toLocalDate();
    boolean covered =
        enrollmentClient.getEnrollments(claim.getEmployeeId()).stream()
            .anyMatch(
                enrollment ->
                    enrollment.planType().equals(claim.getPlanType())
                        && "ACTIVE".equals(enrollment.status())
                        && !enrollment.effectiveDate().isAfter(claimDate));
    ruleTrace.add(
        covered
            ? "coverage: ACTIVE enrollment found for " + claim.getPlanType()
            : "coverage: no ACTIVE enrollment found for " + claim.getPlanType());
    return covered;
  }

  private boolean checkPlanLimit(Claim claim, List<String> ruleTrace) {
    BigDecimal limit = planLimits.get(claim.getPlanType());
    boolean withinLimit = limit != null && claim.getAmountRequested().compareTo(limit) <= 0;
    ruleTrace.add(
        limit == null
            ? "plan-limit: no limit configured for " + claim.getPlanType()
            : "plan-limit: "
                + claim.getAmountRequested()
                + (withinLimit ? " <= " : " > ")
                + limit
                + " limit for "
                + claim.getPlanType());
    return withinLimit;
  }

  private boolean checkDuplicate(Claim claim, List<String> ruleTrace) {
    List<Claim> duplicates =
        claimRepository.findRecentDuplicates(
            claim.getEmployeeId(),
            claim.getPlanType(),
            claim.getAmountRequested(),
            claim.getSubmittedAt().minus(Duration.ofHours(24)),
            claim.getClaimId());
    boolean noDuplicate = duplicates.isEmpty();
    ruleTrace.add(noDuplicate ? "duplicate: none found" : "duplicate: matching recent claim found");
    return noDuplicate;
  }

  private String failureSummary(boolean coveragePassed, boolean planLimitPassed, boolean duplicatePassed) {
    List<String> reasons = new ArrayList<>();
    if (!coveragePassed) {
      reasons.add("no active coverage");
    }
    if (!planLimitPassed) {
      reasons.add("amount exceeds plan limit");
    }
    if (!duplicatePassed) {
      reasons.add("possible duplicate submission");
    }
    return String.join(", ", reasons);
  }
}
