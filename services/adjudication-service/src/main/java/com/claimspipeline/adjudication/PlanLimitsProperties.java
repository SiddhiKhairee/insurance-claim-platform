package com.claimspipeline.adjudication;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Static per-plan-type amount limits, seeded via application.properties rather than a new
 * Mongo collection — a deliberate scope decision, since a 4-entry table that rarely changes
 * doesn't earn a repository, seed step, and CRUD surface. Values are illustrative synthetic
 * limits, not real Mutual of Omaha figures.
 */
@Configuration
public class PlanLimitsProperties {

  @Bean
  @ConfigurationProperties(prefix = "adjudication.plan-limits")
  public Map<String, BigDecimal> planLimits() {
    return new HashMap<>();
  }
}
