package com.claimspipeline.claimsintake;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AdminUserRepository extends MongoRepository<AdminUser, String> {

  Optional<AdminUser> findByUsername(String username);

  boolean existsByUsername(String username);
}
