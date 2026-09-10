package com.claimspipeline.enrollment;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EnrollmentController.class)
class EnrollmentControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private EnrollmentRepository repository;

  @Test
  void createReturnsSavedEnrollment() throws Exception {
    Enrollment request = enrollment(null, "EMP-1", "ACME Co");
    Enrollment saved = enrollment("generated-id", "EMP-1", "ACME Co");
    when(repository.save(any(Enrollment.class))).thenReturn(saved);

    mockMvc
        .perform(
            post("/enrollments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("generated-id"))
        .andExpect(jsonPath("$.employeeId").value("EMP-1"));

    verify(repository).save(any(Enrollment.class));
  }

  @Test
  void createRejectsMissingRequiredFields() throws Exception {
    Enrollment invalid = new Enrollment();

    mockMvc
        .perform(
            post("/enrollments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getByEmployeeIdReturnsMatches() throws Exception {
    Enrollment first = enrollment("id-1", "EMP-2", "ACME Co");
    when(repository.findByEmployeeId(eq("EMP-2"))).thenReturn(List.of(first));

    mockMvc
        .perform(get("/enrollments/EMP-2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].employeeId").value("EMP-2"));
  }

  private Enrollment enrollment(String id, String employeeId, String employer) {
    Enrollment enrollment = new Enrollment();
    enrollment.setId(id);
    enrollment.setEmployeeId(employeeId);
    enrollment.setEmployer(employer);
    enrollment.setPlanType("dental");
    enrollment.setEffectiveDate(LocalDate.of(2026, 1, 1));
    enrollment.setStatus("ACTIVE");
    return enrollment;
  }
}
