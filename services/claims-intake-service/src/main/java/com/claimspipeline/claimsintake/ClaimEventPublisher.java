package com.claimspipeline.claimsintake;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClaimEventPublisher {

  private static final String TOPIC = "claim.submitted";

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public ClaimEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void publishClaimSubmitted(Claim claim) {
    ClaimSubmittedEvent event =
        new ClaimSubmittedEvent(
            claim.getClaimId(),
            claim.getEmployeeId(),
            claim.getPlanType(),
            claim.getAmountRequested(),
            claim.getDescription(),
            claim.getSubmittedAt().toString());
    kafkaTemplate.send(TOPIC, claim.getClaimId(), event);
  }
}
