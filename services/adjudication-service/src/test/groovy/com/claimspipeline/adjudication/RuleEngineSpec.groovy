package com.claimspipeline.adjudication

import java.time.Instant
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.Unroll

class RuleEngineSpec extends Specification {

    EnrollmentClient enrollmentClient = Mock()
    ClaimRepository claimRepository = Mock()
    Map<String, BigDecimal> planLimits = [
        disability: 5000.00,
        dental: 2000.00,
        vision: 500.00,
        life: 10000.00,
    ]

    RuleEngine ruleEngine = new RuleEngine(enrollmentClient, claimRepository, planLimits)

    private static Claim claim(String employeeId, String planType, BigDecimal amount, Instant submittedAt) {
        Claim claim = new Claim()
        claim.setClaimId("CLAIM-1")
        claim.setEmployeeId(employeeId)
        claim.setPlanType(planType)
        claim.setAmountRequested(amount)
        claim.setSubmittedAt(submittedAt)
        return claim
    }

    private static Enrollment activeEnrollment(String employeeId, String planType, LocalDate effectiveDate) {
        return new Enrollment("ENR-1", employeeId, "Acme Co", planType, effectiveDate, "ACTIVE")
    }

    def "approves a claim with active coverage, within plan limit, no duplicate"() {
        given:
        def submittedAt = Instant.parse("2026-01-15T00:00:00Z")
        def testClaim = claim("EMP-1", "dental", 1200.00, submittedAt)
        enrollmentClient.getEnrollments("EMP-1") >> [activeEnrollment("EMP-1", "dental", LocalDate.of(2025, 1, 1))]
        claimRepository.findRecentDuplicates(*_) >> []

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        result.approved()
        result.ruleTrace().size() == 3
    }

    def "denies a claim when no active enrollment is found"() {
        given:
        def testClaim = claim("EMP-1", "dental", 1200.00, Instant.parse("2026-01-15T00:00:00Z"))
        enrollmentClient.getEnrollments("EMP-1") >> []
        claimRepository.findRecentDuplicates(*_) >> []

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        !result.approved()
        result.decisionReason().contains("no active coverage")
    }

    def "denies a claim when the matching enrollment is TERMINATED"() {
        given:
        def testClaim = claim("EMP-1", "dental", 1200.00, Instant.parse("2026-01-15T00:00:00Z"))
        def terminated = new Enrollment("ENR-1", "EMP-1", "Acme Co", "dental", LocalDate.of(2025, 1, 1), "TERMINATED")
        enrollmentClient.getEnrollments("EMP-1") >> [terminated]
        claimRepository.findRecentDuplicates(*_) >> []

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        !result.approved()
        result.decisionReason().contains("no active coverage")
    }

    def "denies a claim when the enrollment effectiveDate is after the claim date"() {
        given:
        def testClaim = claim("EMP-1", "dental", 1200.00, Instant.parse("2026-01-15T00:00:00Z"))
        enrollmentClient.getEnrollments("EMP-1") >> [activeEnrollment("EMP-1", "dental", LocalDate.of(2026, 6, 1))]
        claimRepository.findRecentDuplicates(*_) >> []

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        !result.approved()
        result.decisionReason().contains("no active coverage")
    }

    @Unroll
    def "plan limit check: amount #amount against #planType limit -> approved=#expectedApproved"() {
        given:
        def testClaim = claim("EMP-1", planType, amount, Instant.parse("2026-01-15T00:00:00Z"))
        enrollmentClient.getEnrollments("EMP-1") >> [activeEnrollment("EMP-1", planType, LocalDate.of(2025, 1, 1))]
        claimRepository.findRecentDuplicates(*_) >> []

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        result.approved() == expectedApproved

        where:
        planType     | amount   || expectedApproved
        "dental"     | 2000.00  || true
        "dental"     | 2000.01  || false
        "vision"     | 500.00   || true
        "vision"     | 500.01   || false
        "disability" | 5000.00  || true
        "life"       | 10000.00 || true
    }

    def "denies a claim when a recent matching duplicate exists"() {
        given:
        def testClaim = claim("EMP-1", "dental", 1200.00, Instant.parse("2026-01-15T00:00:00Z"))
        enrollmentClient.getEnrollments("EMP-1") >> [activeEnrollment("EMP-1", "dental", LocalDate.of(2025, 1, 1))]
        claimRepository.findRecentDuplicates(*_) >> [claim("EMP-1", "dental", 1200.00, Instant.parse("2026-01-14T00:00:00Z"))]

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        !result.approved()
        result.decisionReason().contains("possible duplicate submission")
    }

    def "records every failing rule in the trace and reason when multiple rules fail"() {
        given:
        def testClaim = claim("EMP-1", "dental", 9999.00, Instant.parse("2026-01-15T00:00:00Z"))
        enrollmentClient.getEnrollments("EMP-1") >> []
        claimRepository.findRecentDuplicates(*_) >> [claim("EMP-1", "dental", 9999.00, Instant.parse("2026-01-14T00:00:00Z"))]

        when:
        AdjudicationResult result = ruleEngine.evaluate(testClaim)

        then:
        !result.approved()
        result.decisionReason().contains("no active coverage")
        result.decisionReason().contains("amount exceeds plan limit")
        result.decisionReason().contains("possible duplicate submission")
        result.ruleTrace().size() == 3
    }
}
