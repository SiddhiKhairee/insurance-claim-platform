package com.claimspipeline.claimsintake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClaimController.class)
@Import({SecurityConfig.class, AdminTokenConfig.class})
class ClaimControllerTest {

  /**
   * The public GET /claims/{id} body before Phase 8b, captured on 2026-09-28 by running the
   * pre-change controller (main at 6399cf1) with every field populated:
   * {"id":"mongo-id","claimId":"claim-1","employeeId":"EMP-1","planType":"dental",
   * "amountRequested":2500.00,"description":"desc","status":"DENIED",
   * "submittedAt":"2026-09-28T10:00:00Z","adjudicatedAt":"2026-09-28T10:00:02Z",
   * "decisionReason":"Denied: over limit","ruleTrace":["coverage: ACTIVE","planLimit: EXCEEDED"]}
   * Field name -> JSON node type. The frontend and rag-assistant-service read these.
   */
  private static final Map<String, String> PRE_8B_SHAPE =
      Map.ofEntries(
          Map.entry("id", "STRING"),
          Map.entry("claimId", "STRING"),
          Map.entry("employeeId", "STRING"),
          Map.entry("planType", "STRING"),
          Map.entry("amountRequested", "NUMBER"),
          Map.entry("description", "STRING"),
          Map.entry("status", "STRING"),
          Map.entry("submittedAt", "STRING"),
          Map.entry("adjudicatedAt", "STRING"),
          Map.entry("decisionReason", "STRING"),
          Map.entry("ruleTrace", "ARRAY"));

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private ClaimRepository repository;

  @MockBean private ClaimEventPublisher publisher;

  @MockBean private AppealRepository appealRepository;

  @MockBean private AppealService appealService;

  @Test
  void createSavesAndPublishesClaim() throws Exception {
    ClaimRequest request =
        new ClaimRequest("EMP-1", "dental", new BigDecimal("250.00"), "Cleaning");

    when(repository.save(any(Claim.class)))
        .thenAnswer(
            invocation -> {
              Claim claim = invocation.getArgument(0);
              claim.setId("generated-id");
              return claim;
            });

    mockMvc
        .perform(
            post("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("generated-id"))
        .andExpect(jsonPath("$.employeeId").value("EMP-1"))
        .andExpect(jsonPath("$.planType").value("dental"))
        .andExpect(jsonPath("$.status").value("SUBMITTED"))
        .andExpect(jsonPath("$.claimId").exists());

    verify(repository).save(any(Claim.class));
    verify(publisher).publishClaimSubmitted(any(Claim.class));
  }

  @Test
  void createRejectsMissingRequiredFields() throws Exception {
    mockMvc
        .perform(
            post("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRejectsInvalidPlanType() throws Exception {
    ClaimRequest invalid =
        new ClaimRequest("EMP-1", "not-a-real-plan", new BigDecimal("100.00"), "desc");

    mockMvc
        .perform(
            post("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createRejectsNonPositiveAmount() throws Exception {
    ClaimRequest invalid = new ClaimRequest("EMP-1", "dental", new BigDecimal("0"), "desc");

    mockMvc
        .perform(
            post("/claims")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getByClaimIdReturnsClaimWhenFound() throws Exception {
    Claim claim = new Claim();
    claim.setClaimId("claim-1");
    claim.setEmployeeId("EMP-1");
    claim.setPlanType("dental");
    claim.setStatus("APPROVED");

    when(repository.findByClaimId("claim-1")).thenReturn(Optional.of(claim));

    mockMvc
        .perform(get("/claims/claim-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.claimId").value("claim-1"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.displayStatus").value("APPROVED"))
        .andExpect(jsonPath("$.appeal").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void getByClaimIdReturnsNotFoundWhenMissing() throws Exception {
    when(repository.findByClaimId("missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/claims/missing")).andExpect(status().isNotFound());
  }

  /** Amendment 7: every pre-8b field keeps its name and type; only appeal and displayStatus are new. */
  @Test
  void getKeepsThePre8bJsonContractAndOnlyAddsAppealAndDisplayStatus() throws Exception {
    when(repository.findByClaimId("claim-1")).thenReturn(Optional.of(fullDeniedClaim()));
    when(appealRepository.findByClaimId("claim-1")).thenReturn(Optional.of(decidedAppeal()));

    String body =
        mockMvc.perform(get("/claims/claim-1")).andReturn().getResponse().getContentAsString();
    JsonNode json = objectMapper.readTree(body);

    PRE_8B_SHAPE.forEach(
        (field, type) -> {
          assertThat(json.has(field)).as("field %s is still present", field).isTrue();
          assertThat(json.get(field).getNodeType().name()).as("type of %s", field).isEqualTo(type);
        });
    List<String> actualFields = new ArrayList<>();
    json.fieldNames().forEachRemaining(actualFields::add);
    List<String> expected = new ArrayList<>(PRE_8B_SHAPE.keySet());
    expected.add("appeal");
    expected.add("displayStatus");
    assertThat(actualFields).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void getShowsAppealSummaryWithoutStorageKeysOrDocumentIds() throws Exception {
    when(repository.findByClaimId("claim-1")).thenReturn(Optional.of(fullDeniedClaim()));
    when(appealRepository.findByClaimId("claim-1")).thenReturn(Optional.of(decidedAppeal()));

    String body =
        mockMvc
            .perform(get("/claims/claim-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DENIED"))
            .andExpect(jsonPath("$.displayStatus").value("APPROVED_ON_APPEAL"))
            .andExpect(jsonPath("$.appeal.status").value("OVERTURNED"))
            .andExpect(jsonPath("$.appeal.reviewerNote").value("Receipt confirms the amount"))
            .andExpect(jsonPath("$.appeal.documentCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .doesNotContain("storageKey")
        .doesNotContain("appeals/claim-1")
        .doesNotContain("docId")
        .doesNotContain("doc-1")
        .doesNotContain("receipt.pdf")
        .doesNotContain("reviewer\"")
        .doesNotContain("admin-1");
  }

  @Test
  void appealAcceptsMultipartReasonAndFiles() throws Exception {
    Claim claim = fullDeniedClaim();
    Appeal appeal = decidedAppeal();
    appeal.setStatus(AppealStatus.PENDING_REVIEW);
    when(appealService.submit(eq("claim-1"), eq("I have a receipt"), anyList())).thenReturn(appeal);
    when(repository.findByClaimId("claim-1")).thenReturn(Optional.of(claim));

    mockMvc
        .perform(
            multipart("/claims/claim-1/appeal")
                .file(pdf("files"))
                .param("reason", "I have a receipt"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.appeal.status").value("PENDING_REVIEW"))
        .andExpect(jsonPath("$.displayStatus").value("DENIED"));
  }

  @Test
  void appealWithoutReasonIsBadRequest() throws Exception {
    mockMvc
        .perform(multipart("/claims/claim-1/appeal").file(pdf("files")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    verify(appealService, never()).submit(any(), any(), any());
  }

  @Test
  void appealWithoutFilesIsBadRequest() throws Exception {
    mockMvc
        .perform(multipart("/claims/claim-1/appeal").param("reason", "I have a receipt"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
    verify(appealService, never()).submit(any(), any(), any());
  }

  private static MockMultipartFile pdf(String part) {
    return new MockMultipartFile(
        part, "receipt.pdf", "application/pdf", "%PDF-1.4 synthetic".getBytes());
  }

  private static Claim fullDeniedClaim() {
    Claim c = new Claim();
    c.setId("mongo-id");
    c.setClaimId("claim-1");
    c.setEmployeeId("EMP-1");
    c.setPlanType("dental");
    c.setAmountRequested(new BigDecimal("2500.00"));
    c.setDescription("desc");
    c.setStatus("DENIED");
    c.setSubmittedAt(Instant.parse("2026-09-28T10:00:00Z"));
    c.setAdjudicatedAt(Instant.parse("2026-09-28T10:00:02Z"));
    c.setDecisionReason("Denied: over limit");
    c.setRuleTrace(List.of("coverage: ACTIVE", "planLimit: EXCEEDED"));
    return c;
  }

  private static Appeal decidedAppeal() {
    Appeal a = new Appeal();
    a.setClaimId("claim-1");
    a.setReason("I have a receipt");
    a.setDocuments(
        List.of(
            new AppealDocument(
                "doc-1", "appeals/claim-1/doc-1", "receipt.pdf", "application/pdf", 18)));
    a.setSubmittedAt(Instant.parse("2026-09-28T11:00:00Z"));
    a.setStatus(AppealStatus.OVERTURNED);
    a.setReviewer("admin-1");
    a.setDecidedAt(Instant.parse("2026-09-28T12:00:00Z"));
    a.setReviewerNote("Receipt confirms the amount");
    return a;
  }
}
