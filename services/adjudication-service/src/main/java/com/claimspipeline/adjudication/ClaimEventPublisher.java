package com.claimspipeline.adjudication;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClaimEventPublisher {

  private static final String TOPIC = "claim.adjudicated";

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public ClaimEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishClaimAdjudicated(Claim claim) {
    ClaimAdjudicatedEvent event =
        new ClaimAdjudicatedEvent(
            claim.getClaimId(),
            claim.getStatus().toLowerCase(),
            claim.getDecisionReason(),
            claim.getRuleTrace(),
            claim.getAdjudicatedAt().toString());
    kafkaTemplate.send(TOPIC, claim.getClaimId(), event);
  }
}
