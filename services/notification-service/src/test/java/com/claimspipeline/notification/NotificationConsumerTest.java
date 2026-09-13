package com.claimspipeline.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

  @Mock private NotificationLogRepository notificationLogRepository;

  private NotificationConsumer consumer;

  @Test
  void logsAndSavesNotificationForApprovedClaim() {
    consumer = new NotificationConsumer(notificationLogRepository);
    ClaimAdjudicatedEvent event =
        new ClaimAdjudicatedEvent(
            "CLAIM-1", "approved", "Approved: ...", List.of("coverage: ACTIVE"), "2026-01-15T00:00:00Z");

    consumer.onClaimAdjudicated(event);

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository).save(captor.capture());

    NotificationLog saved = captor.getValue();
    assertThat(saved.getClaimId()).isEqualTo("CLAIM-1");
    assertThat(saved.getChannel()).isEqualTo("EMAIL");
    assertThat(saved.getSentAt()).isNotNull();
    assertThat(saved.getMessage()).contains("CLAIM-1").contains("approved").contains("Approved: ...");
  }

  @Test
  void logsAndSavesNotificationForDeniedClaim() {
    consumer = new NotificationConsumer(notificationLogRepository);
    ClaimAdjudicatedEvent event =
        new ClaimAdjudicatedEvent(
            "CLAIM-2", "denied", "Denied: no active coverage", List.of("coverage: none"), "2026-01-15T00:00:00Z");

    consumer.onClaimAdjudicated(event);

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository).save(captor.capture());

    NotificationLog saved = captor.getValue();
    assertThat(saved.getClaimId()).isEqualTo("CLAIM-2");
    assertThat(saved.getMessage()).contains("CLAIM-2").contains("denied").contains("no active coverage");
  }
}
