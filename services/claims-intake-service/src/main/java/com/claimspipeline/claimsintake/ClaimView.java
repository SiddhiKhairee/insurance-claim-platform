package com.claimspipeline.claimsintake;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/**
 * Public response for {@code GET /claims/{claimId}}: every claim field exactly as before
 * (unwrapped, so the frontend and rag-assistant-service read the same names), plus the appeal's
 * public summary and a {@code displayStatus}.
 *
 * <p>{@code status} stays the rule engine's decision. {@code displayStatus} is what the claimant
 * should see: the same as {@code status}, or {@code APPROVED_ON_APPEAL} once a person has
 * overturned the denial. No storage keys, document ids, filenames or reviewer identity appear here.
 */
public record ClaimView(@JsonUnwrapped Claim claim, AppealSummary appeal, String displayStatus) {

  public static final String APPROVED_ON_APPEAL = "APPROVED_ON_APPEAL";

  public static ClaimView of(Claim claim, Appeal appeal) {
    AppealSummary summary = appeal == null ? null : AppealSummary.of(appeal);
    String display =
        appeal != null && appeal.getStatus() == AppealStatus.OVERTURNED
            ? APPROVED_ON_APPEAL
            : claim.getStatus();
    return new ClaimView(claim, summary, display);
  }

  /**
   * The claimant-visible part of an appeal. The reviewer note is shown to the claimant on
   * purpose (it's the reason for the outcome); the admin form in Part 2 says so.
   */
  public record AppealSummary(
      AppealStatus status,
      Instant submittedAt,
      Instant decidedAt,
      String reviewerNote,
      int documentCount) {

    static AppealSummary of(Appeal appeal) {
      int count = appeal.getDocuments() == null ? 0 : appeal.getDocuments().size();
      return new AppealSummary(
          appeal.getStatus(),
          appeal.getSubmittedAt(),
          appeal.getDecidedAt(),
          appeal.getReviewerNote(),
          count);
    }
  }
}
