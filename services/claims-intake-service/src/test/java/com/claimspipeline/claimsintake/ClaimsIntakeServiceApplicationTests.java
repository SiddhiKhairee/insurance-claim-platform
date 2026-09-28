package com.claimspipeline.claimsintake;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full context starts. It needs a real Mongo since Phase 8b: auto-index-creation builds the
 * unique indexes on startup.
 */
@Testcontainers
@SpringBootTest
class ClaimsIntakeServiceApplicationTests {

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Test
  void contextLoads() {}
}
