package com.claimspipeline.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdjudicationConsumerTest {

  @Mock private ClaimRepository claimRepository;
  @Mock private RuleEngine ruleEngine;
  @Mock private ClaimEventPublisher eventPublisher;

  private AdjudicationConsumer consumer;

  @Test
  void appliesApprovedDecisionAndPublishes() {
    consumer = new AdjudicationConsumer(claimRepository, ruleEngine, eventPublisher);

    Claim claim = new Claim();
    claim.setClaimId("CLAIM-1");
    claim.setStatus("SUBMITTED");

    ClaimSubmittedEvent event =
        new ClaimSubmittedEvent(
            "CLAIM-1", "EMP-1", "dental", new BigDecimal("100.00"), "desc", Instant.now().toString());
    when(claimRepository.findByClaimId("CLAIM-1")).thenReturn(Optional.of(claim));
    when(ruleEngine.evaluate(claim))
        .thenReturn(new AdjudicationResult(true, "Approved: ...", List.of("coverage: ACTIVE")));

    consumer.onClaimSubmitted(event);

    ArgumentCaptor<Claim> savedClaim = ArgumentCaptor.forClass(Claim.class);
    verify(claimRepository).save(savedClaim.capture());
    assertThat(savedClaim.getValue().getStatus()).isEqualTo("APPROVED");
    assertThat(savedClaim.getValue().getAdjudicatedAt()).isNotNull();
    verify(eventPublisher).publishClaimAdjudicated(claim);
  }

  @Test
  void appliesDeniedDecisionAndPublishes() {
    consumer = new AdjudicationConsumer(claimRepository, ruleEngine, eventPublisher);

    Claim claim = new Claim();
    claim.setClaimId("CLAIM-1");
    claim.setStatus("SUBMITTED");

    ClaimSubmittedEvent event =
        new ClaimSubmittedEvent(
            "CLAIM-1", "EMP-1", "dental", new BigDecimal("100.00"), "desc", Instant.now().toString());
    when(claimRepository.findByClaimId("CLAIM-1")).thenReturn(Optional.of(claim));
    when(ruleEngine.evaluate(claim))
        .thenReturn(new AdjudicationResult(false, "Denied: no active coverage", List.of("coverage: none")));

    consumer.onClaimSubmitted(event);

    assertThat(claim.getStatus()).isEqualTo("DENIED");
    verify(eventPublisher).publishClaimAdjudicated(claim);
  }

  @Test
  void doesNothingWhenClaimIsNotFound() {
    consumer = new AdjudicationConsumer(claimRepository, ruleEngine, eventPublisher);

    ClaimSubmittedEvent event =
        new ClaimSubmittedEvent(
            "CLAIM-missing", "EMP-1", "dental", new BigDecimal("100.00"), "desc", Instant.now().toString());
    when(claimRepository.findByClaimId("CLAIM-missing")).thenReturn(Optional.empty());

    consumer.onClaimSubmitted(event);

    verify(claimRepository, never()).save(any(Claim.class));
    verify(eventPublisher, never()).publishClaimAdjudicated(any(Claim.class));
  }
}
