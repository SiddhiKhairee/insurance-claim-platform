package com.claimspipeline.claimsintake;

/**
 * One supporting document on an appeal. {@code storageKey} is internal: it never appears in the
 * public claim response, and admins reach the file by {@code docId} through the admin endpoint.
 */
public record AppealDocument(
    String docId, String storageKey, String originalFilename, String contentType, long sizeBytes) {}
