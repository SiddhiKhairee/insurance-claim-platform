package com.claimspipeline.claimsintake;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Picks the appeal-document store from {@code appeals.storage.mode}: {@code local} in dev (no
 * AWS credentials there, so the whole appeal/admin flow still runs), {@code s3} on EC2.
 */
@Configuration
public class DocumentStorageConfig {

  @Bean
  public DocumentStorage documentStorage(
      @Value("${appeals.storage.mode}") String mode,
      @Value("${appeals.storage.local-dir}") String localDir,
      @Value("${appeals.storage.s3-bucket}") String bucket,
      @Value("${appeals.storage.s3-region}") String region) {
    return switch (mode) {
      case "local" -> new LocalDiskDocumentStorage(Path.of(localDir));
      case "s3" -> {
        if (bucket == null || bucket.isBlank()) {
          throw new IllegalStateException(
              "appeals.storage.mode=s3 requires S3_BUCKET to be set in .env");
        }
        // Default credentials chain: on EC2 this resolves to the instance role.
        S3Client client =
            S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        yield new S3DocumentStorage(client, bucket);
      }
      default ->
          throw new IllegalStateException(
              "appeals.storage.mode must be 'local' or 's3', got '" + mode + "'");
    };
  }
}
