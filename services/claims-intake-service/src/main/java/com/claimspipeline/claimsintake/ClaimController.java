package com.claimspipeline.claimsintake;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/claims")
public class ClaimController {

  private final ClaimRepository repository;
  private final ClaimEventPublisher publisher;
  private final AppealRepository appealRepository;
  private final AppealService appealService;

  public ClaimController(
      ClaimRepository repository,
      ClaimEventPublisher publisher,
      AppealRepository appealRepository,
      AppealService appealService) {
    this.repository = repository;
    this.publisher = publisher;
    this.appealRepository = appealRepository;
    this.appealService = appealService;
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
  public ResponseEntity<ClaimView> getByClaimId(@PathVariable String claimId) {
    return repository
        .findByClaimId(claimId)
        .map(claim -> ClaimView.of(claim, appealRepository.findByClaimId(claimId).orElse(null)))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Appeal a DENIED claim with 1-3 supporting documents (PDF, PNG or JPEG). There are no
   * claimant accounts: whoever holds the claim's unguessable UUID can appeal it, a deliberate
   * demo scoping noted in PLAN.md §12.
   */
  @PostMapping(value = "/{claimId}/appeal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ClaimView> appeal(
      @PathVariable String claimId,
      @RequestParam("reason") String reason,
      @RequestParam("files") List<MultipartFile> files) {
    Appeal appeal = appealService.submit(claimId, reason, files);
    Claim claim = repository.findByClaimId(claimId).orElseThrow();
    return ResponseEntity.status(HttpStatus.CREATED).body(ClaimView.of(claim, appeal));
  }
}
