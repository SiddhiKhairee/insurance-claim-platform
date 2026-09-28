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

  @Test
  void logsOverturnedAppealWithReviewerNote() {
    consumer = new NotificationConsumer(notificationLogRepository);
    AppealDecidedEvent event =
        new AppealDecidedEvent(
            "CLAIM-3", "overturned", "Receipt confirms the amount", "2026-09-28T12:00:00Z");

    consumer.onAppealDecided(event);

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository).save(captor.capture());

    NotificationLog saved = captor.getValue();
    assertThat(saved.getClaimId()).isEqualTo("CLAIM-3");
    assertThat(saved.getChannel()).isEqualTo("EMAIL");
    assertThat(saved.getSentAt()).isNotNull();
    assertThat(saved.getMessage())
        .contains("CLAIM-3")
        .contains("approved on appeal")
        .contains("Receipt confirms the amount");
  }

  @Test
  void logsUpheldAppeal() {
    consumer = new NotificationConsumer(notificationLogRepository);
    AppealDecidedEvent event =
        new AppealDecidedEvent("CLAIM-4", "upheld", "Not a covered service", "2026-09-28T12:00:00Z");

    consumer.onAppealDecided(event);

    ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
    verify(notificationLogRepository).save(captor.capture());
    assertThat(captor.getValue().getMessage())
        .contains("CLAIM-4")
        .contains("original denial stands")
        .contains("Not a covered service");
  }
}
