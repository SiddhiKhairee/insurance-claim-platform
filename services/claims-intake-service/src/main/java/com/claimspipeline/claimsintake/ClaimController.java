package com.claimspipeline.claimsintake;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/claims")
public class ClaimController {

  private final ClaimRepository repository;
  private final ClaimEventPublisher publisher;

  public ClaimController(ClaimRepository repository, ClaimEventPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  @PostMapping
  public ResponseEntity<Claim> create(@Valid @RequestBody ClaimRequest request) {
    Claim claim = new Claim();
    claim.setClaimId(UUID.randomUUID().toString());
    claim.setEmployeeId(request.employeeId());
    claim.setPlanType(request.planType());
    claim.setAmountRequested(request.amountRequested());
    claim.setDescription(request.description());
    claim.setStatus("SUBMITTED");
    claim.setSubmittedAt(Instant.now());

    Claim saved = repository.save(claim);
    publisher.publishClaimSubmitted(saved);

    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }

  @GetMapping("/{claimId}")
  public ResponseEntity<Claim> getByClaimId(@PathVariable String claimId) {
    return repository
        .findByClaimId(claimId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
