package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Guarantees that mocks can't prove, against a real Mongo (Testcontainers) and real Tomcat:
 * the unique indexes exist and decide races, a decision can only be made once, and the
 * container's multipart limits answer 413. Kafka isn't needed: the publisher is a mock, so the
 * test can count publishes.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AppealIntegrationTest {

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  static final Path STORAGE_DIR = createTempDir();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    registry.add("appeals.storage.local-dir", STORAGE_DIR::toString);
  }

  @Autowired private TestRestTemplate rest;
  @Autowired private ClaimRepository claims;
  @Autowired private AdminUserRepository adminUsers;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private AdminTokenService tokens;

  @SpyBean private AppealRepository appeals;
  @MockBean private ClaimEventPublisher publisher;

  @Test
  void uniqueIndexesExistOnAppealsClaimIdAndAdminUsername() {
    assertThat(uniqueIndexOn("appeals", "claimId")).isTrue();
    assertThat(uniqueIndexOn("admin_users", "username")).isTrue();
  }

  @Test
  void duplicateAdminUsernameIsRejectedByTheUniqueIndex() {
    adminUsers.insert(admin("dup.admin"));

    assertThatThrownBy(() -> adminUsers.insert(admin("dup.admin")))
        .isInstanceOf(DuplicateKeyException.class);
  }

  /**
   * Two appeals for one claim at the same moment. The service's existsByClaimId pre-check is
   * forced to say "no appeal yet" for both, so both reach the insert and the unique index alone
   * decides: exactly one 201, one 409, and the loser's stored files are removed.
   */
  @Test
  void concurrentAppealsForOneClaimLetExactlyOneThroughAndCleanUpTheLoser() throws Exception {
    String claimId = saveDeniedClaim();
    doReturn(false).when(appeals).existsByClaimId(claimId);

    List<ResponseEntity<String>> responses =
        runConcurrently(() -> postAppeal(claimId, List.of(pdf("a.pdf", 64), pdf("b.pdf", 64))), 2);

    assertThat(responses)
        .extracting(ResponseEntity::getStatusCode)
        .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
    Appeal winner = appeals.findByClaimId(claimId).orElseThrow();
    assertThat(mongoTemplate.getCollection("appeals").countDocuments(new org.bson.Document("claimId", claimId)))
        .isEqualTo(1);
    List<String> expectedDocIds = winner.getDocuments().stream().map(AppealDocument::docId).toList();
    assertThat(storedDocIds(claimId)).containsExactlyInAnyOrderElementsOf(expectedDocIds);
  }

  /** Two admins decide the same appeal at once: one 200, one 409, and one event published. */
  @Test
  void concurrentDecisionsLetExactlyOneWinAndPublishOnce() throws Exception {
    String claimId = saveDeniedClaim();
    Appeal appeal = new Appeal();
    appeal.setClaimId(claimId);
    appeal.setReason("I have a receipt");
    appeal.setDocuments(List.of());
    appeal.setSubmittedAt(Instant.now());
    appeal.setStatus(AppealStatus.PENDING_REVIEW);
    appeals.insert(appeal);
    String token = tokens.issue("admin.one").token();

    List<String> decisions = new ArrayList<>(List.of("OVERTURN", "UPHOLD"));
    List<ResponseEntity<String>> responses =
        runConcurrently(
            () -> {
              String decision;
              synchronized (decisions) {
                decision = decisions.remove(0);
              }
              return postDecision(claimId, decision, token);
            },
            2);

    assertThat(responses)
        .extracting(ResponseEntity::getStatusCode)
        .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
    verify(publisher, times(1)).publishAppealDecided(any(Appeal.class));
    Appeal decided = appeals.findByClaimId(claimId).orElseThrow();
    assertThat(decided.getStatus()).isIn(AppealStatus.OVERTURNED, AppealStatus.UPHELD);
    assertThat(decided.getReviewer()).isEqualTo("admin.one");

    Claim engineRecord = claims.findByClaimId(claimId).orElseThrow();
    assertThat(engineRecord.getStatus()).isEqualTo("DENIED");
    assertThat(engineRecord.getRuleTrace()).containsExactly("coverage: ACTIVE", "planLimit: EXCEEDED");
  }

  /** Amendment 4, through real Tomcat: over 5 MB per file is rejected by the container with 413. */
  @Test
  void fileOverFiveMegabytesIs413() {
    String claimId = saveDeniedClaim();

    ResponseEntity<String> response =
        postAppeal(claimId, List.of(pdf("big.pdf", 5 * 1024 * 1024 + 1)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(response.getBody()).contains("Upload too large");
    assertThat(appeals.findByClaimId(claimId)).isEmpty();
  }

  /** Each file is under 5 MB, but together they exceed the 16 MB request cap: 413. */
  @Test
  void requestOverSixteenMegabytesIs413() {
    String claimId = saveDeniedClaim();
    int part = 4 * 1024 * 1024 + 512 * 1024;

    ResponseEntity<String> response =
        postAppeal(
            claimId,
            List.of(pdf("1.pdf", part), pdf("2.pdf", part), pdf("3.pdf", part), pdf("4.pdf", part)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    assertThat(appeals.findByClaimId(claimId)).isEmpty();
  }

  @Test
  void fileJustUnderTheLimitIsAccepted() {
    String claimId = saveDeniedClaim();

    ResponseEntity<String> response =
        postAppeal(claimId, List.of(pdf("ok.pdf", 5 * 1024 * 1024 - 1024)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // --- helpers ---

  private String saveDeniedClaim() {
    Claim claim = new Claim();
    claim.setClaimId(UUID.randomUUID().toString());
    claim.setEmployeeId("EMP-IT");
    claim.setPlanType("dental");
    claim.setAmountRequested(new BigDecimal("2500.00"));
    claim.setStatus("DENIED");
    claim.setSubmittedAt(Instant.now());
    claim.setAdjudicatedAt(Instant.now());
    claim.setDecisionReason("Denied: amount exceeds plan limit");
    claim.setRuleTrace(List.of("coverage: ACTIVE", "planLimit: EXCEEDED"));
    return claims.save(claim).getClaimId();
  }

  private ResponseEntity<String> postAppeal(String claimId, List<ByteArrayResource> files) {
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    body.add("reason", "I have a receipt");
    for (ByteArrayResource file : files) {
      body.add("files", file);
    }
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    return rest.postForEntity(
        "/claims/" + claimId + "/appeal", new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> postDecision(String claimId, String decision, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(token);
    String json = "{\"decision\":\"" + decision + "\",\"note\":\"Reviewed the receipt\"}";
    return rest.postForEntity(
        "/api/admin/appeals/" + claimId + "/decision", new HttpEntity<>(json, headers), String.class);
  }

  /** A synthetic PDF: a valid %PDF- header padded to the requested size. */
  private static ByteArrayResource pdf(String filename, int size) {
    byte[] content = new byte[size];
    Arrays.fill(content, (byte) ' ');
    byte[] header = "%PDF-1.4 synthetic".getBytes();
    System.arraycopy(header, 0, content, 0, Math.min(header.length, size));
    return new ByteArrayResource(content) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }

  private static <T> List<T> runConcurrently(Callable<T> task, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private List<String> storedDocIds(String claimId) throws IOException {
    Path dir = STORAGE_DIR.resolve("appeals").resolve(claimId);
    if (!Files.exists(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> p.getFileName().toString()).toList();
    }
  }

  private boolean uniqueIndexOn(String collection, String field) {
    return mongoTemplate.indexOps(collection).getIndexInfo().stream()
        .filter(IndexInfo::isUnique)
        .anyMatch(index -> index.isIndexForFields(List.of(field)));
  }

  private static AdminUser admin(String username) {
    AdminUser user = new AdminUser();
    user.setUsername(username);
    user.setPasswordHash("$2a$10$notarealhashbutfineforanindextest.......................");
    user.setCreatedAt(Instant.now());
    return user;
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("appeal-docs-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
