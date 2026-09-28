package com.claimspipeline.claimsintake;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppealRepository extends MongoRepository<Appeal, String> {

  Optional<Appeal> findByClaimId(String claimId);

  boolean existsByClaimId(String claimId);

  List<Appeal> findByStatus(AppealStatus status);
}
