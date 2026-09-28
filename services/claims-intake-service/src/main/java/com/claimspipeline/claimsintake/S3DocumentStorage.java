package com.claimspipeline.claimsintake;

import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Production storage: one private S3 bucket. Credentials come from the EC2 instance role
 * (scoped to s3:PutObject/GetObject on this bucket, PLAN.md §8.4), never from keys in .env. The
 * bucket is never made public; admins read documents only through the admin-only endpoint.
 */
public class S3DocumentStorage implements DocumentStorage {

  private final S3Client s3;
  private final String bucket;

  public S3DocumentStorage(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(content));
  }

  @Override
  public InputStream get(String key) {
    try {
      return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException e) {
      throw new DocumentNotFoundException(key);
    }
  }

  /**
   * Used only to clean up after a failed appeal insert. The instance role doesn't grant
   * s3:DeleteObject today, so on EC2 this fails and is logged by the caller; the object stays
   * orphaned (PLAN.md §12, 2026-09-28).
   */
  @Override
  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }
}
