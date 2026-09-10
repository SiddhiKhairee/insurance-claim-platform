package com.claimspipeline.claimsintake;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClaimController.class)
class ClaimControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private ClaimRepository repository;

  @MockBean private ClaimEventPublisher publisher;

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
}
