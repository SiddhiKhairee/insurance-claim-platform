package com.claimspipeline.claimsintake;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The admin's side of an appeal: list, review, open documents, decide.
 *
 * <p>The decision is a person's, recorded on the appeal (reviewer, time, outcome, note). The claim
 * document, including the rule engine's {@code status}, {@code decisionReason} and
 * {@code ruleTrace}, is never written here.
 */
@Service
public class AdminAppealService {

  private static final Logger LOG = LoggerFactory.getLogger(AdminAppealService.class);
  static final int MAX_NOTE_CHARS = 2000;

  /**
   * Pending first, then oldest first within each group. Done in code on purpose: sorting the
   * status string would put OVERTURNED before PENDING_REVIEW.
   */
  static final Comparator<Appeal> REVIEW_ORDER =
      Comparator.comparing((Appeal a) -> a.getStatus() == AppealStatus.PENDING_REVIEW ? 0 : 1)
          .thenComparing(Appeal::getSubmittedAt, Comparator.nullsLast(Comparator.naturalOrder()));

  private final AppealRepository appeals;
  private final ClaimRepository claims;
  private final DocumentStorage storage;
  private final MongoTemplate mongoTemplate;
  private final ClaimEventPublisher publisher;

  public AdminAppealService(
      AppealRepository appeals,
      ClaimRepository claims,
      DocumentStorage storage,
      MongoTemplate mongoTemplate,
      ClaimEventPublisher publisher) {
    this.appeals = appeals;
    this.claims = claims;
    this.storage = storage;
    this.mongoTemplate = mongoTemplate;
    this.publisher = publisher;
  }

  /** Every appeal (optionally one status), joined with its claim. Unpaginated: demo scale. */
  public List<AppealListItem> list(AppealStatus status) {
    List<Appeal> found = status == null ? appeals.findAll() : appeals.findByStatus(status);
    Map<String, Claim> claimsById =
        claims.findByClaimIdIn(found.stream().map(Appeal::getClaimId).toList()).stream()
            .collect(Collectors.toMap(Claim::getClaimId, Function.identity(), (a, b) -> a));
    return found.stream()
        .sorted(REVIEW_ORDER)
        .map(appeal -> AppealListItem.of(appeal, claimsById.get(appeal.getClaimId())))
        .toList();
  }

  public AppealDetail detail(String claimId) {
    Appeal appeal = findAppeal(claimId);
    Claim claim =
        claims
            .findByClaimId(claimId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
    return AppealDetail.of(claim, appeal);
  }

  public DocumentDownload openDocument(String claimId, String docId) {
    AppealDocument document =
        findAppeal(claimId).getDocuments().stream()
            .filter(d -> d.docId().equals(docId))
            .findFirst()
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    try {
      return new DocumentDownload(document, storage.get(document.storageKey()));
    } catch (DocumentNotFoundException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
    }
  }

  /**
   * Records the admin's decision. The update only matches a PENDING_REVIEW appeal, atomically,
   * so of two concurrent decisions exactly one wins and the other gets 409.
   */
  public AppealDetail decide(String claimId, String decision, String note, String reviewer) {
    AppealStatus outcome = parseDecision(decision);
    String trimmedNote = note == null ? "" : note.strip();
    if (trimmedNote.isEmpty() || trimmedNote.length() > MAX_NOTE_CHARS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A note of 1 to " + MAX_NOTE_CHARS + " characters is required");
    }
    findAppeal(claimId);

    Query pending =
        Query.query(
            Criteria.where("claimId").is(claimId).and("status").is(AppealStatus.PENDING_REVIEW));
    Update update =
        new Update()
            .set("status", outcome)
            .set("reviewer", reviewer)
            .set("decidedAt", Instant.now())
            .set("reviewerNote", trimmedNote);
    Appeal decided =
        mongoTemplate.findAndModify(
            pending, update, FindAndModifyOptions.options().returnNew(true), Appeal.class);
    if (decided == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This appeal has already been decided");
    }

    // The decision is committed at this point. If publishing fails, the decision stands, the
    // failure is logged, and no notification is logged for it; it isn't re-published. An outbox
    // would be the production fix (PLAN.md §12, 2026-09-28).
    try {
      publisher.publishAppealDecided(decided);
    } catch (RuntimeException e) {
      LOG.error("Appeal decided but claim.appeal-decided was not published, claimId={}", claimId, e);
    }

    Claim claim = claims.findByClaimId(claimId).orElseThrow();
    return AppealDetail.of(claim, decided);
  }

  private Appeal findAppeal(String claimId) {
    return appeals
        .findByClaimId(claimId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Appeal not found"));
  }

  private static AppealStatus parseDecision(String decision) {
    if ("UPHOLD".equals(decision)) {
      return AppealStatus.UPHELD;
    }
    if ("OVERTURN".equals(decision)) {
      return AppealStatus.OVERTURNED;
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Decision must be UPHOLD or OVERTURN");
  }

  /** One row of the admin's appeal list. */
  public record AppealListItem(
      String claimId,
      AppealStatus appealStatus,
      Instant appealSubmittedAt,
      Instant decidedAt,
      String planType,
      BigDecimal amountRequested,
      String engineStatus,
      String decisionReason,
      int documentCount) {

    static AppealListItem of(Appeal appeal, Claim claim) {
      return new AppealListItem(
          appeal.getClaimId(),
          appeal.getStatus(),
          appeal.getSubmittedAt(),
          appeal.getDecidedAt(),
          claim == null ? null : claim.getPlanType(),
          claim == null ? null : claim.getAmountRequested(),
          claim == null ? null : claim.getStatus(),
          claim == null ? null : claim.getDecisionReason(),
          appeal.getDocuments() == null ? 0 : appeal.getDocuments().size());
    }
  }

  /** Everything the admin reviews: the engine's decision as written, and the appeal. */
  public record AppealDetail(
      String claimId,
      String employeeId,
      String planType,
      BigDecimal amountRequested,
      String description,
      Instant claimSubmittedAt,
      Instant adjudicatedAt,
      String engineStatus,
      String decisionReason,
      List<String> ruleTrace,
      String displayStatus,
      AppealInfo appeal) {

    static AppealDetail of(Claim claim, Appeal appeal) {
      return new AppealDetail(
          claim.getClaimId(),
          claim.getEmployeeId(),
          claim.getPlanType(),
          claim.getAmountRequested(),
          claim.getDescription(),
          claim.getSubmittedAt(),
          claim.getAdjudicatedAt(),
          claim.getStatus(),
          claim.getDecisionReason(),
          claim.getRuleTrace(),
          ClaimView.of(claim, appeal).displayStatus(),
          AppealInfo.of(appeal));
    }
  }

  /** The appeal as the admin sees it: documents by id and metadata, never by storage key. */
  public record AppealInfo(
      String reason,
      AppealStatus status,
      Instant submittedAt,
      String reviewer,
      Instant decidedAt,
      String reviewerNote,
      List<DocumentInfo> documents) {

    static AppealInfo of(Appeal appeal) {
      List<DocumentInfo> docs =
          appeal.getDocuments() == null
              ? List.of()
              : appeal.getDocuments().stream()
                  .map(
                      d ->
                          new DocumentInfo(
                              d.docId(), d.originalFilename(), d.contentType(), d.sizeBytes()))
                  .toList();
      return new AppealInfo(
          appeal.getReason(),
          appeal.getStatus(),
          appeal.getSubmittedAt(),
          appeal.getReviewer(),
          appeal.getDecidedAt(),
          appeal.getReviewerNote(),
          docs);
    }
  }

  public record DocumentInfo(String docId, String filename, String contentType, long sizeBytes) {}

  public record DocumentDownload(AppealDocument document, InputStream content) {}
}
