package com.claimspipeline.claimsintake;

/** A document key with no stored object behind it. */
public class DocumentNotFoundException extends RuntimeException {

  public DocumentNotFoundException(String key) {
    super("No document stored under " + key);
  }
}
