package com.claimspipeline.claimsintake;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClaimRepository extends MongoRepository<Claim, String> {

  Optional<Claim> findByClaimId(String claimId);
}
