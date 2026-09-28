package com.claimspipeline.claimsintake;

import java.io.InputStream;

/**
 * Where appeal supporting documents live. S3 in production (the private bucket, reached through
 * the EC2 instance role), a local directory in dev, which has no AWS credentials. The
 * implementation is chosen by {@code appeals.storage.mode} in {@link DocumentStorageConfig}.
 *
 * <p>Keys are always server-generated ({@code appeals/{claimId}/{docId}}); a user-supplied
 * filename never becomes part of a key or a path.
 */
public interface DocumentStorage {

  void put(String key, byte[] content, String contentType);

  /** Opens the stored document for streaming. The caller closes the stream. */
  InputStream get(String key);

  void delete(String key);
}
