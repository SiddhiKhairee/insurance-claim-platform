package com.claimspipeline.adjudication;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Fails closed: if Enrollment Service is unreachable or errors, this returns an empty list
 * rather than throwing, so the coverage check in {@link RuleEngine} denies the claim instead
 * of the Kafka listener thread dying on an uncaught exception. This is a deliberate business
 * tradeoff (false denials during an Enrollment Service outage vs. the risk of approving a
 * claim with unverifiable coverage) — see the PR description for the full rationale.
 */
@Component
public class EnrollmentClient {

  private static final Logger LOG = LoggerFactory.getLogger(EnrollmentClient.class);

  private final RestTemplate restTemplate;
  private final String enrollmentServiceUrl;

  public EnrollmentClient(
      RestTemplate restTemplate,
      @Value("${enrollment.service.url}") String enrollmentServiceUrl) {
    this.restTemplate = restTemplate;
    this.enrollmentServiceUrl = enrollmentServiceUrl;
  }

  public List<Enrollment> getEnrollments(String employeeId) {
    try {
      Enrollment[] enrollments =
          restTemplate.getForObject(
              enrollmentServiceUrl + "/enrollments/{employeeId}", Enrollment[].class, employeeId);
      return enrollments == null ? List.of() : List.of(enrollments);
    } catch (RestClientException e) {
      LOG.warn("Enrollment Service call failed for employeeId={}: {}", employeeId, e.getMessage());
      return List.of();
    }
  }
}
