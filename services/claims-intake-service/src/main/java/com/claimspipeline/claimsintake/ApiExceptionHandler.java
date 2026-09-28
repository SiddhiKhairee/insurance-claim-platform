package com.claimspipeline.claimsintake;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns the API's expected failures into small JSON bodies ({@code {"error": "..."}}) that the
 * frontend can show. Messages are fixed strings written in this service, never exception details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** Our own rejections (404/409/400/403/401/429), with the message we chose. */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
    String message = e.getReason() == null ? "Request failed" : e.getReason();
    return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", message));
  }

  /**
   * An upload over the container's multipart limits (5 MB per file, 16 MB per request). 413,
   * rather than 400, because the request was well-formed, just too large.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("error", "Upload too large: at most 3 documents of 5 MB each"));
  }

  /** A multipart request without the {@code files} part or the {@code reason} field. */
  @ExceptionHandler({
    MissingServletRequestPartException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<Map<String, String>> handleMissingPart(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("error", "A reason and at least one supporting document are required"));
  }
}
