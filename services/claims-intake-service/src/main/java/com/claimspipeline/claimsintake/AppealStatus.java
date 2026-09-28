package com.claimspipeline.claimsintake;

/** Lifecycle of an appeal: submitted by the claimant, then decided once by an admin. */
public enum AppealStatus {
  PENDING_REVIEW,
  UPHELD,
  OVERTURNED
}
