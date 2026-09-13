package com.claimspipeline.adjudication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Reads and writes the same {@code claims} collection that claims-intake-service creates
 * documents in. This is a sanctioned exception to the "no cross-service collection reads"
 * rule: PLAN.md §5.3 explicitly specifies adjudication-service writes the decision and
 * ruleTrace back onto the existing claim document, rather than owning a separate copy.
 */
public interface ClaimRepository extends MongoRepository<Claim, String> {

  Optional<Claim> findByClaimId(String claimId);

  @Query(
      "{ 'employeeId': ?0, 'planType': ?1, 'amountRequested': ?2, "
          + "'status': { $in: ['SUBMITTED', 'APPROVED'] }, 'submittedAt': { $gte: ?3 }, "
          + "'claimId': { $ne: ?4 } }")
  List<Claim> findRecentDuplicates(
      String employeeId,
      String planType,
      BigDecimal amountRequested,
      Instant since,
      String excludingClaimId);
}
