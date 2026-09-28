package com.claimspipeline.claimsintake;

import java.util.Optional;

/**
 * Identifies an uploaded document from its first bytes (its "magic number"). The Content-Type
 * header and filename extension are set by the client and so aren't trusted: a text file renamed
 * to .pdf is rejected. Only the three types appeals accept are recognised.
 */
public final class FileTypeDetector {

  private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

  private FileTypeDetector() {}

  /** The detected MIME type, or empty if the bytes aren't a PDF, PNG or JPEG. */
  public static Optional<String> detect(byte[] content) {
    if (startsWith(content, PDF)) {
      return Optional.of("application/pdf");
    }
    if (startsWith(content, PNG)) {
      return Optional.of("image/png");
    }
    if (startsWith(content, JPEG)) {
      return Optional.of("image/jpeg");
    }
    return Optional.empty();
  }

  private static boolean startsWith(byte[] content, byte[] prefix) {
    if (content == null || content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (content[i] != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
