package com.claimspipeline.notification;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumer.class);
  private static final String CHANNEL = "EMAIL";

  private final NotificationLogRepository notificationLogRepository;

  public NotificationConsumer(NotificationLogRepository notificationLogRepository) {
    this.notificationLogRepository = notificationLogRepository;
  }

  @KafkaListener(topics = "claim.adjudicated", groupId = "notification-service")
  public void onClaimAdjudicated(ClaimAdjudicatedEvent event) {
    String message =
        "Your claim %s was %s: %s".formatted(event.claimId(), event.status(), event.decisionReason());

    NotificationLog notificationLog = new NotificationLog();
    notificationLog.setClaimId(event.claimId());
    notificationLog.setChannel(CHANNEL);
    notificationLog.setSentAt(Instant.now());
    notificationLog.setMessage(message);
    notificationLogRepository.save(notificationLog);

    LOG.info("Simulated notification sent: claimId={} channel={} message={}", event.claimId(), CHANNEL, message);
  }

  /**
   * An admin decided an appeal. The reviewer's note is included on purpose: it's the claimant's
   * explanation of the outcome (PLAN.md §12, 2026-09-28).
   */
  @KafkaListener(
      topics = "claim.appeal-decided",
      groupId = "notification-service",
      containerFactory = "appealDecidedListenerContainerFactory")
  public void onAppealDecided(AppealDecidedEvent event) {
    String outcome =
        "overturned".equals(event.outcome())
            ? "overturned: the claim is approved on appeal"
            : "upheld: the original denial stands";
    String message =
        "Your appeal for claim %s was %s. Reviewer note: %s"
            .formatted(event.claimId(), outcome, event.reviewerNote());

    NotificationLog notificationLog = new NotificationLog();
    notificationLog.setClaimId(event.claimId());
    notificationLog.setChannel(CHANNEL);
    notificationLog.setSentAt(Instant.now());
    notificationLog.setMessage(message);
    notificationLogRepository.save(notificationLog);

    LOG.info("Simulated notification sent: claimId={} channel={} message={}", event.claimId(), CHANNEL, message);
  }
}
