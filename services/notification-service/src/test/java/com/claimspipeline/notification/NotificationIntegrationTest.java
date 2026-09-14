package com.claimspipeline.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * One minimal end-to-end pipeline test against real Kafka and Mongo containers: publish a
 * claim.adjudicated-shaped message, confirm notification-service consumes it and writes a
 * notifications_log document. Mirrors adjudication-service's AdjudicationIntegrationTest.
 */
@Testcontainers
@SpringBootTest
class NotificationIntegrationTest {

  @Container
  static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @Container static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
  }

  @Autowired private NotificationLogRepository notificationLogRepository;

  private KafkaProducer<String, String> producer;

  @AfterEach
  void tearDown() {
    if (producer != null) {
      producer.close();
    }
  }

  @Test
  void consumesClaimAdjudicatedAndWritesNotificationLog() {
    producer = new KafkaProducer<>(producerProps());
    String payload =
        """
        {"claimId":"CLAIM-IT-1","status":"approved","decisionReason":"Approved: ...",\
        "ruleTrace":["coverage: ACTIVE"],"adjudicatedAt":"2026-01-15T00:00:00Z"}
        """;
    producer.send(new ProducerRecord<>("claim.adjudicated", "CLAIM-IT-1", payload));
    producer.flush();

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              List<NotificationLog> logs = notificationLogRepository.findAll();
              assertThat(logs).anySatisfy(log -> assertThat(log.getClaimId()).isEqualTo("CLAIM-IT-1"));
            });

    NotificationLog saved =
        notificationLogRepository.findAll().stream()
            .filter(log -> "CLAIM-IT-1".equals(log.getClaimId()))
            .findFirst()
            .orElseThrow();
    assertThat(saved.getChannel()).isEqualTo("EMAIL");
    assertThat(saved.getSentAt()).isNotNull();
    assertThat(saved.getMessage()).contains("approved");
  }

  private Map<String, Object> producerProps() {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
  }
}
