package com.claimspipeline.claimsintake;

import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (SecurityConfig): review appealed claims and uphold or overturn the denial. */
@RestController
@RequestMapping("/api/admin/appeals")
public class AdminAppealController {

  private final AdminAppealService service;

  public AdminAppealController(AdminAppealService service) {
    this.service = service;
  }

  @GetMapping
  public List<AdminAppealService.AppealListItem> list(
      @RequestParam(value = "status", required = false) AppealStatus status) {
    return service.list(status);
  }

  @GetMapping("/{claimId}")
  public AdminAppealService.AppealDetail detail(@PathVariable String claimId) {
    return service.detail(claimId);
  }

  /**
   * Streams a supporting document from storage; the bucket itself stays private. Served as an
   * attachment with nosniff, and with the type detected at upload, not the one the client claimed.
   */
  @GetMapping("/{claimId}/documents/{docId}")
  public ResponseEntity<InputStreamResource> document(
      @PathVariable String claimId, @PathVariable String docId) {
    AdminAppealService.DocumentDownload download = service.openDocument(claimId, docId);
    AppealDocument document = download.document();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(document.contentType()))
        .contentLength(document.sizeBytes())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            // Stored filenames are already sanitised to ASCII (AppealService.sanitizeFilename), so
            // the plain filename parameter is enough.
            ContentDisposition.attachment().filename(document.originalFilename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(new InputStreamResource(download.content()));
  }

  @PostMapping("/{claimId}/decision")
  public AdminAppealService.AppealDetail decide(
      @PathVariable String claimId,
      @RequestBody DecisionRequest request,
      @AuthenticationPrincipal Jwt admin) {
    return service.decide(claimId, request.decision(), request.note(), admin.getSubject());
  }

  public record DecisionRequest(String decision, String note) {}
}
