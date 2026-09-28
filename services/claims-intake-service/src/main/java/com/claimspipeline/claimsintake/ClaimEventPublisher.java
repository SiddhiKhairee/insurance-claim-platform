package com.claimspipeline.claimsintake;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ClaimEventPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(ClaimEventPublisher.class);

  private static final String TOPIC = "claim.submitted";
  private static final String APPEAL_DECIDED_TOPIC = "claim.appeal-decided";

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

  public void publishAppealDecided(Appeal appeal) {
    AppealDecidedEvent event =
        new AppealDecidedEvent(
            appeal.getClaimId(),
            appeal.getStatus().name().toLowerCase(Locale.ROOT),
            appeal.getReviewerNote(),
            appeal.getDecidedAt().toString());
    // send() is asynchronous: a broker-side failure shows up here, after the caller has returned.
    // A synchronous failure (e.g. no broker metadata) is thrown to the caller, which logs it.
    kafkaTemplate
        .send(APPEAL_DECIDED_TOPIC, appeal.getClaimId(), event)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                LOG.error(
                    "Failed to publish {} for claimId={}; no notification will be logged",
                    APPEAL_DECIDED_TOPIC,
                    appeal.getClaimId(),
                    ex);
              }
            });
  }
}
