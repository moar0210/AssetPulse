package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventHandler;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventRepository;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingWorker;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "assetpulse.telemetry.processing.enabled=true",
            "assetpulse.telemetry.processing.initial-delay-millis=3600000",
            "assetpulse.telemetry.processing.poll-delay-millis=3600000"
        })
@Testcontainers
class TelemetryProcessingWorkerEnabledIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SENSOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-21T08:00:00Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-08-21T09:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private ApplicationContext applicationContext;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private TelemetryProcessingEventRepository eventRepository;
    @Autowired private TelemetryProcessingWorker worker;

    @BeforeEach
    void resetTelemetryAndAlerts() {
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void enabledWorkerUsesTheSingleProductionHandlerAndDrainsOnePendingEvent() {
        UUID batchId = UUID.randomUUID();
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_batch (
                            id,
                            organisation_id,
                            idempotency_key,
                            request_fingerprint,
                            reading_count,
                            accepted_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            'enabled-worker-alert',
                            :requestFingerprint,
                            1,
                            :acceptedAt
                        )
                        """)
                .param("id", batchId)
                .param("organisationId", NORTHSTAR_ID)
                .param("requestFingerprint", "0".repeat(64))
                .param("acceptedAt", ACCEPTED_AT.atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_reading (
                            id,
                            organisation_id,
                            batch_id,
                            sequence_number,
                            sensor_id,
                            value,
                            observed_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            :batchId,
                            0,
                            :sensorId,
                            90.000000,
                            :observedAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("organisationId", NORTHSTAR_ID)
                .param("batchId", batchId)
                .param("sensorId", SENSOR_ID)
                .param("observedAt", OBSERVED_AT.atOffset(ZoneOffset.UTC))
                .update();
        eventRepository.insertBatchAccepted(NORTHSTAR_ID, batchId, ACCEPTED_AT);

        assertThat(applicationContext.getBeansOfType(TelemetryProcessingWorker.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(TelemetryProcessingEventHandler.class))
                .hasSize(1)
                .containsValue(applicationContext.getBean(ThresholdAlertHandler.class));

        worker.poll();

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT status
                                        FROM telemetry_processing_event
                                        WHERE telemetry_batch_id = :batchId
                                        """)
                                .param("batchId", batchId)
                                .query(String.class)
                                .single())
                .isEqualTo("COMPLETED");
        assertThat(jdbcClient.sql("SELECT occurrence_count FROM alert").query(Long.class).single())
                .isOne();
    }
}
