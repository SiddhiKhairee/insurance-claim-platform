package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Both storage implementations. S3 is exercised only against a mocked client here; the real
 * bucket and instance role are first used in Part 2's live EC2 check (PLAN.md §12).
 */
class DocumentStorageTest {

  @TempDir Path baseDir;

  @Test
  void localDiskRoundTripsAndDeletes() throws Exception {
    LocalDiskDocumentStorage storage = new LocalDiskDocumentStorage(baseDir);
    byte[] content = "%PDF-1.4 synthetic".getBytes();

    storage.put("appeals/claim-1/doc-1", content, "application/pdf");

    assertThat(Files.exists(baseDir.resolve("appeals/claim-1/doc-1"))).isTrue();
    try (InputStream in = storage.get("appeals/claim-1/doc-1")) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
    storage.delete("appeals/claim-1/doc-1");
    assertThat(Files.exists(baseDir.resolve("appeals/claim-1/doc-1"))).isFalse();
  }

  @Test
  void localDiskRefusesKeysThatEscapeTheBaseDirectory() {
    LocalDiskDocumentStorage storage = new LocalDiskDocumentStorage(baseDir.resolve("docs"));

    assertThatThrownBy(() -> storage.put("../outside", new byte[] {1}, "application/pdf"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> storage.get("appeals/../../outside"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.exists(baseDir.resolve("outside"))).isFalse();
  }

  @Test
  void localDiskMissingDocumentIsNotFound() {
    LocalDiskDocumentStorage storage = new LocalDiskDocumentStorage(baseDir);

    assertThatThrownBy(() -> storage.get("appeals/claim-1/missing"))
        .isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  void s3PutUsesTheConfiguredBucketKeyAndDetectedType() {
    S3Client s3 = mock(S3Client.class);
    S3DocumentStorage storage = new S3DocumentStorage(s3, "claims-bucket");

    storage.put("appeals/claim-1/doc-1", new byte[] {1, 2, 3}, "image/png");

    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3).putObject(request.capture(), any(RequestBody.class));
    assertThat(request.getValue().bucket()).isEqualTo("claims-bucket");
    assertThat(request.getValue().key()).isEqualTo("appeals/claim-1/doc-1");
    assertThat(request.getValue().contentType()).isEqualTo("image/png");
  }

  @Test
  void s3GetStreamsTheObject() throws Exception {
    S3Client s3 = mock(S3Client.class);
    ResponseInputStream<GetObjectResponse> body =
        new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(new byte[] {7, 8})));
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(body);
    S3DocumentStorage storage = new S3DocumentStorage(s3, "claims-bucket");

    try (InputStream in = storage.get("appeals/claim-1/doc-1")) {
      assertThat(in.readAllBytes()).containsExactly(7, 8);
    }
    ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3).getObject(request.capture());
    assertThat(request.getValue().bucket()).isEqualTo("claims-bucket");
    assertThat(request.getValue().key()).isEqualTo("appeals/claim-1/doc-1");
  }

  @Test
  void s3MissingKeyIsNotFound() {
    S3Client s3 = mock(S3Client.class);
    when(s3.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("missing").build());
    S3DocumentStorage storage = new S3DocumentStorage(s3, "claims-bucket");

    assertThatThrownBy(() -> storage.get("appeals/claim-1/missing"))
        .isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  void s3DeleteTargetsTheKey() {
    S3Client s3 = mock(S3Client.class);
    new S3DocumentStorage(s3, "claims-bucket").delete("appeals/claim-1/doc-1");

    ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3).deleteObject(request.capture());
    assertThat(request.getValue().key()).isEqualTo("appeals/claim-1/doc-1");
  }

  @Test
  void fileTypeIsDetectedFromMagicBytes() {
    assertThat(FileTypeDetector.detect("%PDF-1.7 rest".getBytes())).contains("application/pdf");
    assertThat(
            FileTypeDetector.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}))
        .contains("image/png");
    assertThat(FileTypeDetector.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}))
        .contains("image/jpeg");
    assertThat(FileTypeDetector.detect("plain text".getBytes())).isEmpty();
    assertThat(FileTypeDetector.detect("<svg>".getBytes())).isEmpty();
    assertThat(FileTypeDetector.detect(new byte[] {'%', 'P'})).isEmpty();
    assertThat(FileTypeDetector.detect(null)).isEmpty();
  }
}
