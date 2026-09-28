package com.claimspipeline.claimsintake;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A claimant's appeal of a DENIED claim, and the admin's decision on it.
 *
 * <p>Kept in its own collection rather than as a sub-document of the claim, deliberately: the
 * claim document is the rule engine's record (adjudication-service writes it back whole), so an
 * appeal never touches the engine's {@code status}, {@code decisionReason} or {@code ruleTrace},
 * and a re-save of the claim can't drop the appeal. The unique index on {@code claimId} enforces
 * one appeal per claim, including under concurrent requests.
 */
@Document(collection = "appeals")
public class Appeal {

  @Id private String id;

  @Indexed(unique = true)
  private String claimId;

  private String reason;
  private List<AppealDocument> documents;
  private Instant submittedAt;
  private AppealStatus status;
  private String reviewer;
  private Instant decidedAt;
  private String reviewerNote;

  public Appeal() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getClaimId() {
    return claimId;
  }

  public void setClaimId(String claimId) {
    this.claimId = claimId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public List<AppealDocument> getDocuments() {
    return documents;
  }

  public void setDocuments(List<AppealDocument> documents) {
    this.documents = documents;
  }

  public Instant getSubmittedAt() {
    return submittedAt;
  }

  public void setSubmittedAt(Instant submittedAt) {
    this.submittedAt = submittedAt;
  }

  public AppealStatus getStatus() {
    return status;
  }

  public void setStatus(AppealStatus status) {
    this.status = status;
  }

  public String getReviewer() {
    return reviewer;
  }

  public void setReviewer(String reviewer) {
    this.reviewer = reviewer;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public void setDecidedAt(Instant decidedAt) {
    this.decidedAt = decidedAt;
  }

  public String getReviewerNote() {
    return reviewerNote;
  }

  public void setReviewerNote(String reviewerNote) {
    this.reviewerNote = reviewerNote;
  }
}
