package com.claimspipeline.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

@ExtendWith(MockitoExtension.class)
class ClaimEventPublisherTest {

  @Mock private KafkaTemplate<String, Object> kafkaTemplate;

  private ClaimEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = new ClaimEventPublisher(kafkaTemplate);
  }

  @Test
  void publishesToClaimAdjudicatedTopicKeyedByClaimIdWithLowercaseStatus() {
    Claim claim = new Claim();
    claim.setClaimId("CLAIM-1");
    claim.setEmployeeId("EMP-1");
    claim.setPlanType("dental");
    claim.setAmountRequested(new BigDecimal("100.00"));
    claim.setStatus("APPROVED");
    claim.setDecisionReason("Approved: active coverage found, within plan limit, no duplicate found.");
    claim.setRuleTrace(List.of("coverage: ACTIVE enrollment found for dental"));
    claim.setAdjudicatedAt(Instant.parse("2026-01-15T00:00:00Z"));

    publisher.publishClaimAdjudicated(claim);

    ArgumentCaptor<ClaimAdjudicatedEvent> eventCaptor = ArgumentCaptor.forClass(ClaimAdjudicatedEvent.class);
    verify(kafkaTemplate).send(eq("claim.adjudicated"), eq("CLAIM-1"), eventCaptor.capture());

    ClaimAdjudicatedEvent event = eventCaptor.getValue();
    assertThat(event.claimId()).isEqualTo("CLAIM-1");
    assertThat(event.status()).isEqualTo("approved");
    assertThat(event.decisionReason()).isEqualTo(claim.getDecisionReason());
    assertThat(event.ruleTrace()).isEqualTo(claim.getRuleTrace());
    assertThat(event.adjudicatedAt()).isEqualTo("2026-01-15T00:00:00Z");
  }
}
