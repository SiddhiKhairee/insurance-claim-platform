package com.claimspipeline.claimsintake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Accepts a claimant's appeal of a DENIED claim.
 *
 * <p>This path never writes the claim document: the rule engine's {@code status},
 * {@code decisionReason} and {@code ruleTrace} stay exactly as adjudication-service wrote them.
 * The appeal is stored separately in {@code appeals}, and a person decides it later
 * ({@link AdminAppealService}). Nothing here reads, scores or classifies a document's content
 * beyond checking its file type.
 */
@Service
public class AppealService {

  static final int MAX_FILES = 3;
  static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
  static final int MAX_REASON_CHARS = 2000;

  private static final Logger LOG = LoggerFactory.getLogger(AppealService.class);

  private final ClaimRepository claimRepository;
  private final AppealRepository appealRepository;
  private final DocumentStorage storage;

  public AppealService(
      ClaimRepository claimRepository, AppealRepository appealRepository, DocumentStorage storage) {
    this.claimRepository = claimRepository;
    this.appealRepository = appealRepository;
    this.storage = storage;
  }

  public Appeal submit(String claimId, String reason, List<MultipartFile> files) {
    Claim claim =
        claimRepository
            .findByClaimId(claimId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Claim not found"));
    if (!"DENIED".equals(claim.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Only denied claims can be appealed");
    }
    if (appealRepository.existsByClaimId(claimId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This claim has already been appealed");
    }

    String trimmedReason = reason == null ? "" : reason.strip();
    if (trimmedReason.isEmpty() || trimmedReason.length() > MAX_REASON_CHARS) {
      throw badRequest("A reason of 1 to " + MAX_REASON_CHARS + " characters is required");
    }
    List<ValidatedFile> validated = validate(files);

    List<AppealDocument> documents = new ArrayList<>();
    try {
      for (ValidatedFile file : validated) {
        String docId = UUID.randomUUID().toString();
        String key = "appeals/" + claimId + "/" + docId;
        storage.put(key, file.content(), file.contentType());
        documents.add(
            new AppealDocument(
                docId, key, file.filename(), file.contentType(), file.content().length));
      }
    } catch (RuntimeException e) {
      deleteQuietly(documents);
      throw e;
    }

    Appeal appeal = new Appeal();
    appeal.setClaimId(claimId);
    appeal.setReason(trimmedReason);
    appeal.setDocuments(documents);
    appeal.setSubmittedAt(Instant.now());
    appeal.setStatus(AppealStatus.PENDING_REVIEW);
    try {
      return appealRepository.insert(appeal);
    } catch (DuplicateKeyException e) {
      // Lost a race with a concurrent appeal for the same claim: the unique index on claimId
      // rejected this one, so its files are removed. A crash between put() and insert() can
      // still leave orphaned files; accepted at this scale (PLAN.md §12, 2026-09-28).
      deleteQuietly(documents);
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This claim has already been appealed");
    }
  }

  private List<ValidatedFile> validate(List<MultipartFile> files) {
    List<MultipartFile> present =
        files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    if (present.isEmpty()) {
      throw badRequest("At least one supporting document is required");
    }
    if (present.size() > MAX_FILES) {
      throw badRequest("At most " + MAX_FILES + " documents can be attached");
    }
    List<ValidatedFile> validated = new ArrayList<>();
    for (MultipartFile file : present) {
      // Spring's multipart limit (5 MB per file) normally rejects this first, with 413; this
      // check is the second line of defence if that limit is ever loosened.
      if (file.getSize() > MAX_FILE_BYTES) {
        throw badRequest("Each document must be 5 MB or smaller");
      }
      byte[] content;
      try {
        content = file.getBytes();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      String contentType =
          FileTypeDetector.detect(content)
              .orElseThrow(() -> badRequest("Only PDF, PNG or JPEG documents are accepted"));
      validated.add(new ValidatedFile(sanitizeFilename(file.getOriginalFilename()), contentType, content));
    }
    return validated;
  }

  /** Display name only (it never becomes a storage key or path): safe characters, bounded length. */
  static String sanitizeFilename(String original) {
    if (original == null || original.isBlank()) {
      return "document";
    }
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    name = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
    if (name.isEmpty()) {
      return "document";
    }
    return name.length() > 100 ? name.substring(0, 100) : name;
  }

  private void deleteQuietly(List<AppealDocument> documents) {
    for (AppealDocument document : documents) {
      try {
        storage.delete(document.storageKey());
      } catch (RuntimeException e) {
        LOG.error("Could not delete orphaned appeal document key={}", document.storageKey(), e);
      }
    }
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private record ValidatedFile(String filename, String contentType, byte[] content) {}
}
