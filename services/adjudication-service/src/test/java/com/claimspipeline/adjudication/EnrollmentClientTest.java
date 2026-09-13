package com.claimspipeline.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class EnrollmentClientTest {

  @Mock private RestTemplate restTemplate;

  private EnrollmentClient client;

  @BeforeEach
  void setUp() {
    client = new EnrollmentClient(restTemplate, "http://localhost:8081");
  }

  @Test
  void returnsEnrollmentsFromEnrollmentService() {
    Enrollment enrollment =
        new Enrollment("ENR-1", "EMP-1", "Acme Co", "dental", LocalDate.of(2025, 1, 1), "ACTIVE");
    when(restTemplate.getForObject(any(String.class), eq(Enrollment[].class), eq("EMP-1")))
        .thenReturn(new Enrollment[] {enrollment});

    List<Enrollment> result = client.getEnrollments("EMP-1");

    assertThat(result).containsExactly(enrollment);
  }

  @Test
  void returnsEmptyListWhenEnrollmentServiceIsUnreachable() {
    when(restTemplate.getForObject(any(String.class), eq(Enrollment[].class), eq("EMP-1")))
        .thenThrow(new RestClientException("connection refused"));

    List<Enrollment> result = client.getEnrollments("EMP-1");

    assertThat(result).isEmpty();
  }

  @Test
  void returnsEmptyListWhenResponseBodyIsNull() {
    when(restTemplate.getForObject(any(String.class), eq(Enrollment[].class), eq("EMP-1")))
        .thenReturn(null);

    List<Enrollment> result = client.getEnrollments("EMP-1");

    assertThat(result).isEmpty();
  }
}
