package com.claimspipeline.claimsintake;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** Dev-only storage: documents are files under a base directory (a Docker volume in compose). */
public class LocalDiskDocumentStorage implements DocumentStorage {

  private final Path baseDir;

  public LocalDiskDocumentStorage(Path baseDir) {
    this.baseDir = baseDir.toAbsolutePath().normalize();
  }

  @Override
  public void put(String key, byte[] content, String contentType) {
    Path target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not store document " + key, e);
    }
  }

  @Override
  public InputStream get(String key) {
    try {
      return Files.newInputStream(resolve(key));
    } catch (NoSuchFileException e) {
      throw new DocumentNotFoundException(key);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read document " + key, e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not delete document " + key, e);
    }
  }

  /** Keys are server-generated, but a key that would escape the base directory is refused anyway. */
  private Path resolve(String key) {
    Path resolved = baseDir.resolve(key).normalize();
    if (!resolved.startsWith(baseDir) || resolved.equals(baseDir)) {
      throw new IllegalArgumentException("Invalid document key");
    }
    return resolved;
  }
}
