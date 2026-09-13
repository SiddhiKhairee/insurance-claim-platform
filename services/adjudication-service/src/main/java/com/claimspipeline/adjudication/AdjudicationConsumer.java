package com.claimspipeline.adjudication;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class AdjudicationConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(AdjudicationConsumer.class);

  private final ClaimRepository claimRepository;
  private final RuleEngine ruleEngine;
  private final ClaimEventPublisher eventPublisher;

  public AdjudicationConsumer(
      ClaimRepository claimRepository, RuleEngine ruleEngine, ClaimEventPublisher eventPublisher) {
    this.claimRepository = claimRepository;
    this.ruleEngine = ruleEngine;
    this.eventPublisher = eventPublisher;
  }

  @KafkaListener(topics = "claim.submitted", groupId = "adjudication-service")
  public void onClaimSubmitted(ClaimSubmittedEvent event) {
    // Mongo is the source of truth for the claim; the event only signals that one was
    // submitted. Reading the Claim here (rather than reconstructing it from the event)
    // keeps a single write path and avoids re-deriving fields already persisted by
    // claims-intake-service. This is the sanctioned same-collection write-back
    // documented on ClaimRepository.
    claimRepository
        .findByClaimId(event.claimId())
        .ifPresentOrElse(this::adjudicate, () -> LOG.warn("No claim found for claimId={}", event.claimId()));
  }

  private void adjudicate(Claim claim) {
    AdjudicationResult result = ruleEngine.evaluate(claim);
    claim.setStatus(result.approved() ? "APPROVED" : "DENIED");
    claim.setDecisionReason(result.decisionReason());
    claim.setRuleTrace(result.ruleTrace());
    claim.setAdjudicatedAt(Instant.now());
    claimRepository.save(claim);
    eventPublisher.publishClaimAdjudicated(claim);
  }
}
