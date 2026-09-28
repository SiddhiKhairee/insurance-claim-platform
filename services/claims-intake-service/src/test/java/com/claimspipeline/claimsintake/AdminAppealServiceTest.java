package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminAppealServiceTest {

  @Mock private AppealRepository appeals;
  @Mock private ClaimRepository claims;
  @Mock private DocumentStorage storage;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private ClaimEventPublisher publisher;

  private AdminAppealService service;

  @BeforeEach
  void setUp() {
    service = new AdminAppealService(appeals, claims, storage, mongoTemplate, publisher);
  }

  /**
   * Amendment 5: pending first, then oldest first. The insert order and statuses are chosen so a
   * plain alphabetical sort on status (OVERTURNED < PENDING_REVIEW < UPHELD) would get it wrong.
   */
  @Test
  void listPutsPendingFirstThenOldestFirst() {
    Appeal upheldOld = appeal("c-upheld", AppealStatus.UPHELD, "2026-09-01T00:00:00Z");
    Appeal overturned = appeal("c-overturned", AppealStatus.OVERTURNED, "2026-09-02T00:00:00Z");
    Appeal pendingNew = appeal("c-pending-new", AppealStatus.PENDING_REVIEW, "2026-09-10T00:00:00Z");
    Appeal pendingOld = appeal("c-pending-old", AppealStatus.PENDING_REVIEW, "2026-09-05T00:00:00Z");
    when(appeals.findAll()).thenReturn(List.of(overturned, pendingNew, upheldOld, pendingOld));
    when(claims.findByClaimIdIn(anyCollection())).thenReturn(List.of(deniedClaim("c-pending-old")));

    List<AdminAppealService.AppealListItem> list = service.list(null);

    assertThat(list)
        .extracting(AdminAppealService.AppealListItem::claimId)
        .containsExactly("c-pending-old", "c-pending-new", "c-upheld", "c-overturned");
    assertThat(list.get(0).engineStatus()).isEqualTo("DENIED");
    assertThat(list.get(0).decisionReason()).isEqualTo("Denied: amount exceeds plan limit");
  }

  @Test
  void listCanFilterByStatus() {
    when(appeals.findByStatus(AppealStatus.UPHELD)).thenReturn(List.of());
    when(claims.findByClaimIdIn(anyCollection())).thenReturn(List.of());

    assertThat(service.list(AppealStatus.UPHELD)).isEmpty();
    verify(appeals, never()).findAll();
  }

  @Test
  void detailShowsTheEngineDecisionAndDocumentMetadataButNoStorageKey() {
    Appeal appeal = appeal("claim-1", AppealStatus.PENDING_REVIEW, "2026-09-10T00:00:00Z");
    when(appeals.findByClaimId("claim-1")).thenReturn(Optional.of(appeal));
    when(claims.findByClaimId("claim-1")).thenReturn(Optional.of(deniedClaim("claim-1")));

    AdminAppealService.AppealDetail detail = service.detail("claim-1");

    assertThat(detail.engineStatus()).isEqualTo("DENIED");
    assertThat(detail.ruleTrace()).containsExactly("coverage: ACTIVE", "planLimit: EXCEEDED");
    assertThat(detail.appeal().documents())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.docId()).isEqualTo("doc-1");
              assertThat(d.filename()).isEqualTo("receipt.pdf");
            });
    assertThat(detail.toString()).doesNotContain("appeals/claim-1/doc-1");
  }

  @Test
  void unknownDocumentIsNotFound() {
    when(appeals.findByClaimId("claim-1"))
        .thenReturn(Optional.of(appeal("claim-1", AppealStatus.PENDING_REVIEW, "2026-09-10T00:00:00Z")));

    assertStatus(() -> service.openDocument("claim-1", "other-doc"), HttpStatus.NOT_FOUND);
  }

  @Test
  void openDocumentStreamsFromStorageByKey() {
    when(appeals.findByClaimId("claim-1"))
        .thenReturn(Optional.of(appeal("claim-1", AppealStatus.PENDING_REVIEW, "2026-09-10T00:00:00Z")));
    when(storage.get("appeals/claim-1/doc-1")).thenReturn(new ByteArrayInputStream(new byte[] {1}));

    AdminAppealService.DocumentDownload download = service.openDocument("claim-1", "doc-1");

    assertThat(download.document().docId()).isEqualTo("doc-1");
    assertThat(download.content()).isNotNull();
  }

  @Test
  void decisionRequiresANote() {
    assertStatus(() -> service.decide("claim-1", "OVERTURN", "  ", "admin.one"), HttpStatus.BAD_REQUEST);
    assertStatus(() -> service.decide("claim-1", "OVERTURN", null, "admin.one"), HttpStatus.BAD_REQUEST);
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Appeal.class));
  }

  @Test
  void decisionMustBeUpholdOrOverturn() {
    assertStatus(() -> service.decide("claim-1", "APPROVE", "note", "admin.one"), HttpStatus.BAD_REQUEST);
  }

  @Test
  void overturnRecordsTheReviewerAndPublishesWithoutTouchingTheClaim() {
    Claim claim = givenPendingAppealAndClaim();
    Appeal decided = appeal("claim-1", AppealStatus.OVERTURNED, "2026-09-10T00:00:00Z");
    decided.setReviewer("admin.one");
    decided.setReviewerNote("Receipt confirms the amount");
    decided.setDecidedAt(Instant.parse("2026-09-11T00:00:00Z"));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Appeal.class)))
        .thenReturn(decided);

    AdminAppealService.AppealDetail detail =
        service.decide("claim-1", "OVERTURN", " Receipt confirms the amount ", "admin.one");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(Appeal.class));
    assertThat(query.getValue().getQueryObject().get("status")).isEqualTo(AppealStatus.PENDING_REVIEW);
    org.bson.Document set = (org.bson.Document) update.getValue().getUpdateObject().get("$set");
    assertThat(set.get("status")).isEqualTo(AppealStatus.OVERTURNED);
    assertThat(set.get("reviewer")).isEqualTo("admin.one");
    assertThat(set.get("reviewerNote")).isEqualTo("Receipt confirms the amount");
    assertThat(set.get("decidedAt")).isNotNull();
    assertThat(set.keySet()).doesNotContain("ruleTrace", "decisionReason");
    verify(publisher).publishAppealDecided(decided);

    assertThat(detail.displayStatus()).isEqualTo(ClaimView.APPROVED_ON_APPEAL);
    assertThat(detail.engineStatus()).isEqualTo("DENIED");
    assertEngineRecordUntouched(claim);
  }

  @Test
  void upholdKeepsTheDenial() {
    Claim claim = givenPendingAppealAndClaim();
    Appeal decided = appeal("claim-1", AppealStatus.UPHELD, "2026-09-10T00:00:00Z");
    decided.setDecidedAt(Instant.parse("2026-09-11T00:00:00Z"));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Appeal.class)))
        .thenReturn(decided);

    AdminAppealService.AppealDetail detail =
        service.decide("claim-1", "UPHOLD", "Receipt is for a different service", "admin.one");

    assertThat(detail.displayStatus()).isEqualTo("DENIED");
    assertThat(detail.appeal().status()).isEqualTo(AppealStatus.UPHELD);
    assertEngineRecordUntouched(claim);
  }

  @Test
  void alreadyDecidedAppealIsConflictAndPublishesNothing() {
    when(appeals.findByClaimId("claim-1"))
        .thenReturn(Optional.of(appeal("claim-1", AppealStatus.UPHELD, "2026-09-10T00:00:00Z")));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Appeal.class)))
        .thenReturn(null);

    assertStatus(() -> service.decide("claim-1", "OVERTURN", "note", "admin.one"), HttpStatus.CONFLICT);
    verify(publisher, never()).publishAppealDecided(any());
  }

  @Test
  void unknownAppealIsNotFound() {
    when(appeals.findByClaimId("claim-1")).thenReturn(Optional.empty());

    assertStatus(() -> service.decide("claim-1", "UPHOLD", "note", "admin.one"), HttpStatus.NOT_FOUND);
  }

  /** Amendment 3: the decision is committed before publishing, so a publish failure doesn't undo it. */
  @Test
  void publishFailureStillReturnsTheCommittedDecision() {
    givenPendingAppealAndClaim();
    Appeal decided = appeal("claim-1", AppealStatus.OVERTURNED, "2026-09-10T00:00:00Z");
    decided.setDecidedAt(Instant.parse("2026-09-11T00:00:00Z"));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Appeal.class)))
        .thenReturn(decided);
    doThrow(new RuntimeException("broker down")).when(publisher).publishAppealDecided(decided);

    AdminAppealService.AppealDetail detail =
        service.decide("claim-1", "OVERTURN", "Receipt confirms the amount", "admin.one");

    assertThat(detail.appeal().status()).isEqualTo(AppealStatus.OVERTURNED);
    assertThat(detail.displayStatus()).isEqualTo(ClaimView.APPROVED_ON_APPEAL);
  }

  private Claim givenPendingAppealAndClaim() {
    Claim claim = deniedClaim("claim-1");
    when(appeals.findByClaimId("claim-1"))
        .thenReturn(Optional.of(appeal("claim-1", AppealStatus.PENDING_REVIEW, "2026-09-10T00:00:00Z")));
    when(claims.findByClaimId("claim-1")).thenReturn(Optional.of(claim));
    return claim;
  }

  /** The rule engine's decision survives the appeal exactly as written. */
  private void assertEngineRecordUntouched(Claim claim) {
    verify(claims, never()).save(any(Claim.class));
    assertThat(claim.getStatus()).isEqualTo("DENIED");
    assertThat(claim.getDecisionReason()).isEqualTo("Denied: amount exceeds plan limit");
    assertThat(claim.getRuleTrace()).containsExactly("coverage: ACTIVE", "planLimit: EXCEEDED");
  }

  private static Appeal appeal(String claimId, AppealStatus status, String submittedAt) {
    Appeal appeal = new Appeal();
    appeal.setClaimId(claimId);
    appeal.setReason("I have a receipt");
    appeal.setStatus(status);
    appeal.setSubmittedAt(Instant.parse(submittedAt));
    appeal.setDocuments(
        List.of(
            new AppealDocument(
                "doc-1", "appeals/" + claimId + "/doc-1", "receipt.pdf", "application/pdf", 10)));
    return appeal;
  }

  private static Claim deniedClaim(String claimId) {
    Claim claim = new Claim();
    claim.setClaimId(claimId);
    claim.setPlanType("dental");
    claim.setAmountRequested(new BigDecimal("2500.00"));
    claim.setStatus("DENIED");
    claim.setDecisionReason("Denied: amount exceeds plan limit");
    claim.setRuleTrace(List.of("coverage: ACTIVE", "planLimit: EXCEEDED"));
    return claim;
  }

  private static void assertStatus(Runnable call, HttpStatus expected) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(expected);
  }
}
