package com.claimspipeline.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Runs a labeled synthetic claim set directly against {@link RuleEngine} (not through the
 * live HTTP/Kafka pipeline) and computes real precision/recall — the number logged in
 * PLAN.md §11. Each case's expected label was assigned by hand based on the same rule
 * definitions the engine implements, so this measures whether the implementation matches
 * its own spec, which is the correct thing to measure for a deterministic rule engine.
 */
@ExtendWith(MockitoExtension.class)
class RuleEnginePrecisionRecallTest {

  private record LabeledClaim(Claim claim, List<Enrollment> enrollments, boolean expectedApproved) {}

  @Mock private EnrollmentClient enrollmentClient;
  @Mock private ClaimRepository claimRepository;

  private static Claim claim(String employeeId, String planType, BigDecimal amount, Instant submittedAt) {
    Claim claim = new Claim();
    claim.setClaimId("CLAIM-" + employeeId + "-" + planType + "-" + amount);
    claim.setEmployeeId(employeeId);
    claim.setPlanType(planType);
    claim.setAmountRequested(amount);
    claim.setSubmittedAt(submittedAt);
    return claim;
  }

  private static Enrollment active(String employeeId, String planType, LocalDate effectiveDate) {
    return new Enrollment("ENR-" + employeeId, employeeId, "Acme Co", planType, effectiveDate, "ACTIVE");
  }

  private static Enrollment terminated(String employeeId, String planType, LocalDate effectiveDate) {
    return new Enrollment("ENR-" + employeeId, employeeId, "Acme Co", planType, effectiveDate, "TERMINATED");
  }

  private List<LabeledClaim> buildLabeledDataset() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    List<LabeledClaim> dataset = new ArrayList<>();

    // Clearly approve: active coverage, well within limit.
    for (int i = 0; i < 10; i++) {
      String emp = "EMP-A" + i;
      dataset.add(
          new LabeledClaim(
              claim(emp, "dental", new BigDecimal("500.00"), now),
              List.of(active(emp, "dental", LocalDate.of(2024, 1, 1))),
              true));
    }
    // Clearly approve: different plan types, at-limit amounts.
    dataset.add(
        new LabeledClaim(
            claim("EMP-B1", "vision", new BigDecimal("500.00"), now),
            List.of(active("EMP-B1", "vision", LocalDate.of(2024, 1, 1))),
            true));
    dataset.add(
        new LabeledClaim(
            claim("EMP-B2", "disability", new BigDecimal("5000.00"), now),
            List.of(active("EMP-B2", "disability", LocalDate.of(2024, 1, 1))),
            true));
    dataset.add(
        new LabeledClaim(
            claim("EMP-B3", "life", new BigDecimal("10000.00"), now),
            List.of(active("EMP-B3", "life", LocalDate.of(2024, 1, 1))),
            true));

    // Clearly deny: no enrollment at all.
    for (int i = 0; i < 5; i++) {
      String emp = "EMP-C" + i;
      dataset.add(new LabeledClaim(claim(emp, "dental", new BigDecimal("200.00"), now), List.of(), false));
    }
    // Clearly deny: terminated enrollment.
    for (int i = 0; i < 5; i++) {
      String emp = "EMP-D" + i;
      dataset.add(
          new LabeledClaim(
              claim(emp, "dental", new BigDecimal("200.00"), now),
              List.of(terminated(emp, "dental", LocalDate.of(2024, 1, 1))),
              false));
    }
    // Clearly deny: over the plan limit.
    for (int i = 0; i < 5; i++) {
      String emp = "EMP-E" + i;
      dataset.add(
          new LabeledClaim(
              claim(emp, "vision", new BigDecimal("501.00"), now),
              List.of(active(emp, "vision", LocalDate.of(2024, 1, 1))),
              false));
    }

    // Edge case: enrollment effective the same day as the claim -> approve.
    dataset.add(
        new LabeledClaim(
            claim("EMP-F1", "dental", new BigDecimal("100.00"), now),
            List.of(active("EMP-F1", "dental", LocalDate.of(2026, 6, 1))),
            true));
    // Edge case: enrollment effective one day after the claim -> deny.
    dataset.add(
        new LabeledClaim(
            claim("EMP-F2", "dental", new BigDecimal("100.00"), now),
            List.of(active("EMP-F2", "dental", LocalDate.of(2026, 6, 2))),
            false));
    // Edge case: amount exactly one cent over the limit -> deny.
    dataset.add(
        new LabeledClaim(
            claim("EMP-F3", "dental", new BigDecimal("2000.01"), now),
            List.of(active("EMP-F3", "dental", LocalDate.of(2024, 1, 1))),
            false));
    // Edge case: enrollment for a different plan type than requested -> deny.
    dataset.add(
        new LabeledClaim(
            claim("EMP-F4", "dental", new BigDecimal("100.00"), now),
            List.of(active("EMP-F4", "vision", LocalDate.of(2024, 1, 1))),
            false));
    // Edge case: employee has multiple enrollments, only one matches plan+active -> approve.
    dataset.add(
        new LabeledClaim(
            claim("EMP-F5", "dental", new BigDecimal("100.00"), now),
            List.of(
                terminated("EMP-F5", "dental", LocalDate.of(2023, 1, 1)),
                active("EMP-F5", "dental", LocalDate.of(2024, 1, 1))),
            true));

    return dataset;
  }

  @Test
  void reportsPrecisionAndRecallAgainstLabeledSyntheticDataset() {
    Map<String, BigDecimal> planLimits =
        Map.of(
            "disability", new BigDecimal("5000.00"),
            "dental", new BigDecimal("2000.00"),
            "vision", new BigDecimal("500.00"),
            "life", new BigDecimal("10000.00"));
    RuleEngine ruleEngine = new RuleEngine(enrollmentClient, claimRepository, planLimits);
    when(claimRepository.findRecentDuplicates(anyString(), anyString(), any(BigDecimal.class), any(), anyString()))
        .thenReturn(List.of());

    List<LabeledClaim> dataset = buildLabeledDataset();
    int truePositives = 0;
    int falsePositives = 0;
    int trueNegatives = 0;
    int falseNegatives = 0;

    for (LabeledClaim labeled : dataset) {
      when(enrollmentClient.getEnrollments(labeled.claim().getEmployeeId())).thenReturn(labeled.enrollments());
      boolean actualApproved = ruleEngine.evaluate(labeled.claim()).approved();

      if (labeled.expectedApproved() && actualApproved) {
        truePositives++;
      } else if (!labeled.expectedApproved() && actualApproved) {
        falsePositives++;
      } else if (!labeled.expectedApproved() && !actualApproved) {
        trueNegatives++;
      } else {
        falseNegatives++;
      }
    }

    double precision = truePositives / (double) (truePositives + falsePositives);
    double recall = truePositives / (double) (truePositives + falseNegatives);

    System.out.printf(
        "RuleEngine precision/recall on n=%d labeled synthetic claims: "
            + "TP=%d FP=%d TN=%d FN=%d precision=%.3f recall=%.3f%n",
        dataset.size(), truePositives, falsePositives, trueNegatives, falseNegatives, precision, recall);

    assertThat(falsePositives).isZero();
    assertThat(falseNegatives).isZero();
    assertThat(precision).isEqualTo(1.0);
    assertThat(recall).isEqualTo(1.0);
  }
}
