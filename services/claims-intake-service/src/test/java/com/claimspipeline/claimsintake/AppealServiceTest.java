package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AppealServiceTest {

  private static final byte[] PDF_BYTES = "%PDF-1.4 synthetic receipt".getBytes();

  @Mock private ClaimRepository claimRepository;
  @Mock private AppealRepository appealRepository;
  @Mock private DocumentStorage storage;

  private AppealService service;

  @BeforeEach
  void setUp() {
    service = new AppealService(claimRepository, appealRepository, storage);
  }

  @Test
  void unknownClaimIsNotFound() {
    when(claimRepository.findByClaimId("missing")).thenReturn(Optional.empty());

    assertStatus(() -> service.submit("missing", "reason", List.of(pdf())), HttpStatus.NOT_FOUND);
  }

  @Test
  void approvedClaimCannotBeAppealed() {
    Claim claim = deniedClaim();
    claim.setStatus("APPROVED");
    when(claimRepository.findByClaimId("claim-1")).thenReturn(Optional.of(claim));

    assertStatus(() -> service.submit("claim-1", "reason", List.of(pdf())), HttpStatus.CONFLICT);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  @Test
  void claimStillSubmittedCannotBeAppealed() {
    Claim claim = deniedClaim();
    claim.setStatus("SUBMITTED");
    when(claimRepository.findByClaimId("claim-1")).thenReturn(Optional.of(claim));

    assertStatus(() -> service.submit("claim-1", "reason", List.of(pdf())), HttpStatus.CONFLICT);
  }

  @Test
  void secondAppealIsConflict() {
    givenDeniedClaim();
    when(appealRepository.existsByClaimId("claim-1")).thenReturn(true);

    assertStatus(() -> service.submit("claim-1", "reason", List.of(pdf())), HttpStatus.CONFLICT);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  @Test
  void blankReasonIsBadRequest() {
    givenDeniedClaim();

    assertStatus(() -> service.submit("claim-1", "   ", List.of(pdf())), HttpStatus.BAD_REQUEST);
  }

  @Test
  void overlongReasonIsBadRequest() {
    givenDeniedClaim();
    String reason = "x".repeat(AppealService.MAX_REASON_CHARS + 1);

    assertStatus(() -> service.submit("claim-1", reason, List.of(pdf())), HttpStatus.BAD_REQUEST);
  }

  @Test
  void noFileIsBadRequest() {
    givenDeniedClaim();
    MultipartFile empty = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);

    assertStatus(() -> service.submit("claim-1", "reason", List.of()), HttpStatus.BAD_REQUEST);
    assertStatus(() -> service.submit("claim-1", "reason", List.of(empty)), HttpStatus.BAD_REQUEST);
    assertStatus(() -> service.submit("claim-1", "reason", null), HttpStatus.BAD_REQUEST);
  }

  @Test
  void fourFilesIsBadRequest() {
    givenDeniedClaim();

    assertStatus(
        () -> service.submit("claim-1", "reason", List.of(pdf(), pdf(), pdf(), pdf())),
        HttpStatus.BAD_REQUEST);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  /** Second line of defence: Spring's multipart limit normally rejects this first (413). */
  @Test
  void fileOverFiveMegabytesIsBadRequest() {
    givenDeniedClaim();
    MultipartFile big = mock(MultipartFile.class);
    when(big.isEmpty()).thenReturn(false);
    when(big.getSize()).thenReturn(AppealService.MAX_FILE_BYTES + 1);

    assertStatus(() -> service.submit("claim-1", "reason", List.of(big)), HttpStatus.BAD_REQUEST);
  }

  @Test
  void textFileRenamedToPdfIsBadRequest() {
    givenDeniedClaim();
    MultipartFile fake =
        new MockMultipartFile("files", "receipt.pdf", "application/pdf", "just text".getBytes());

    assertStatus(() -> service.submit("claim-1", "reason", List.of(fake)), HttpStatus.BAD_REQUEST);
    verify(storage, never()).put(anyString(), any(), anyString());
  }

  @Test
  void validAppealStoresDocumentsAndInsertsPendingAppeal() {
    Claim claim = givenDeniedClaim();
    when(appealRepository.insert(any(Appeal.class))).thenAnswer(inv -> inv.getArgument(0));

    Appeal appeal = service.submit("claim-1", "  I have a receipt  ", List.of(pdf()));

    assertThat(appeal.getStatus()).isEqualTo(AppealStatus.PENDING_REVIEW);
    assertThat(appeal.getReason()).isEqualTo("I have a receipt");
    assertThat(appeal.getSubmittedAt()).isNotNull();
    assertThat(appeal.getDocuments()).hasSize(1);
    AppealDocument doc = appeal.getDocuments().get(0);
    assertThat(doc.storageKey()).isEqualTo("appeals/claim-1/" + doc.docId());
    assertThat(doc.contentType()).isEqualTo("application/pdf");
    assertThat(doc.originalFilename()).isEqualTo("receipt.pdf");
    assertThat(doc.sizeBytes()).isEqualTo(PDF_BYTES.length);
    verify(storage).put(doc.storageKey(), PDF_BYTES, "application/pdf");

    assertClaimUntouched(claim);
  }

  @Test
  void contentTypeComesFromTheBytesNotTheClient() {
    givenDeniedClaim();
    when(appealRepository.insert(any(Appeal.class))).thenAnswer(inv -> inv.getArgument(0));
    byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};
    MultipartFile mislabeled = new MockMultipartFile("files", "scan.pdf", "application/pdf", png);

    Appeal appeal = service.submit("claim-1", "reason", List.of(mislabeled));

    assertThat(appeal.getDocuments().get(0).contentType()).isEqualTo("image/png");
  }

  @Test
  void losingAConcurrentInsertDeletesItsFilesAndIsConflict() {
    givenDeniedClaim();
    when(appealRepository.insert(any(Appeal.class))).thenThrow(new DuplicateKeyException("dup"));

    assertStatus(
        () -> service.submit("claim-1", "reason", List.of(pdf(), pdf())), HttpStatus.CONFLICT);

    ArgumentCaptor<String> put = ArgumentCaptor.forClass(String.class);
    verify(storage, times(2)).put(put.capture(), any(), anyString());
    verify(storage).delete(put.getAllValues().get(0));
    verify(storage).delete(put.getAllValues().get(1));
  }

  @Test
  void storageFailureMidwayRemovesAlreadyStoredFiles() {
    givenDeniedClaim();
    // First document stores, second fails: the first must be removed again.
    doNothing()
        .doThrow(new RuntimeException("disk full"))
        .when(storage)
        .put(anyString(), any(), anyString());

    assertThatThrownBy(() -> service.submit("claim-1", "reason", List.of(pdf(), pdf())))
        .hasMessage("disk full");

    ArgumentCaptor<String> put = ArgumentCaptor.forClass(String.class);
    verify(storage, times(2)).put(put.capture(), any(), anyString());
    verify(storage).delete(put.getAllValues().get(0));
    verify(storage, never()).delete(put.getAllValues().get(1));
    verify(appealRepository, never()).insert(any(Appeal.class));
  }

  @Test
  void filenamesAreSanitisedForDisplay() {
    assertThat(AppealService.sanitizeFilename("../../etc/passwd")).isEqualTo("passwd");
    assertThat(AppealService.sanitizeFilename("C:\\docs\\my<receipt>.pdf")).isEqualTo("my_receipt_.pdf");
    assertThat(AppealService.sanitizeFilename(null)).isEqualTo("document");
    assertThat(AppealService.sanitizeFilename("a".repeat(300))).hasSize(100);
  }

  /** The engine's record is never written by an appeal: no save, and the fields are as they were. */
  private void assertClaimUntouched(Claim claim) {
    verify(claimRepository, never()).save(any(Claim.class));
    assertThat(claim.getStatus()).isEqualTo("DENIED");
    assertThat(claim.getDecisionReason()).isEqualTo("Denied: amount exceeds plan limit");
    assertThat(claim.getRuleTrace()).containsExactly("coverage: ACTIVE", "planLimit: EXCEEDED");
  }

  private Claim givenDeniedClaim() {
    Claim claim = deniedClaim();
    when(claimRepository.findByClaimId(eq("claim-1"))).thenReturn(Optional.of(claim));
    return claim;
  }

  private static Claim deniedClaim() {
    Claim claim = new Claim();
    claim.setClaimId("claim-1");
    claim.setPlanType("dental");
    claim.setAmountRequested(new BigDecimal("2500.00"));
    claim.setStatus("DENIED");
    claim.setAdjudicatedAt(Instant.parse("2026-09-28T10:00:02Z"));
    claim.setDecisionReason("Denied: amount exceeds plan limit");
    claim.setRuleTrace(List.of("coverage: ACTIVE", "planLimit: EXCEEDED"));
    return claim;
  }

  private static MultipartFile pdf() {
    return new MockMultipartFile("files", "receipt.pdf", "application/pdf", PDF_BYTES);
  }

  private static void assertStatus(Runnable call, HttpStatus expected) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(expected);
  }
}
