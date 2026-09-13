package com.claimspipeline.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * One minimal end-to-end pipeline test against real Redpanda-compatible Kafka and real Mongo
 * containers: publish a claim.submitted-shaped message, confirm adjudication-service consumes
 * it, updates the claims collection, and publishes claim.adjudicated. Enrollment Service is
 * mocked (not containerized) since standing up a full sibling service is out of scope for this
 * single proof-of-pipeline test; broader integration coverage is deferred to Phase 9.
 */
@Testcontainers
@SpringBootTest
class AdjudicationIntegrationTest {

  @Container
  static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private ClaimRepository claimRepository;

  @MockBean private EnrollmentClient enrollmentClient;

  private KafkaProducer<String, String> producer;
  private KafkaConsumer<String, String> consumer;

  @AfterEach
  void tearDown() {
    if (producer != null) {
      producer.close();
    }
    if (consumer != null) {
      consumer.close();
    }
  }

  @Test
  void consumesClaimSubmittedAndPublishesClaimAdjudicated() {
    Claim claim = new Claim();
    claim.setClaimId("CLAIM-IT-1");
    claim.setEmployeeId("EMP-IT-1");
    claim.setPlanType("dental");
    claim.setAmountRequested(new BigDecimal("500.00"));
    claim.setStatus("SUBMITTED");
    claim.setSubmittedAt(Instant.now());
    claimRepository.save(claim);

    when(enrollmentClient.getEnrollments("EMP-IT-1"))
        .thenReturn(
            List.of(new Enrollment("ENR-IT-1", "EMP-IT-1", "Acme Co", "dental", LocalDate.now().minusDays(30), "ACTIVE")));

    producer = new KafkaProducer<>(producerProps());
    String payload =
        """
        {"claimId":"CLAIM-IT-1","employeeId":"EMP-IT-1","planType":"dental",\
        "amountRequested":500.00,"description":"integration test","submittedAt":"%s"}
        """
            .formatted(claim.getSubmittedAt());
    producer.send(new ProducerRecord<>("claim.submitted", "CLAIM-IT-1", payload));
    producer.flush();

    consumer = new KafkaConsumer<>(consumerProps());
    consumer.subscribe(List.of("claim.adjudicated"));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
              assertThat(records.count()).isGreaterThan(0);
              ConsumerRecord<String, String> record = records.iterator().next();
              assertThat(record.value()).contains("\"claimId\":\"CLAIM-IT-1\"");
              assertThat(record.value()).contains("\"status\":\"approved\"");
            });

    Optional<Claim> updated = claimRepository.findByClaimId("CLAIM-IT-1");
    assertThat(updated).isPresent();
    assertThat(updated.get().getStatus()).isEqualTo("APPROVED");
    assertThat(updated.get().getRuleTrace()).isNotEmpty();
  }

  private Map<String, Object> producerProps() {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
  }

  private Map<String, Object> consumerProps() {
    return Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.GROUP_ID_CONFIG, "adjudication-integration-test",
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
  }
}
