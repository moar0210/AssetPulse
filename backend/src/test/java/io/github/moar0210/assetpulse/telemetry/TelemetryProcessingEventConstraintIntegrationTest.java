package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class TelemetryProcessingEventConstraintIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void clearTelemetry() {
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void rejectsAnEventWithoutItsTenantBatch() {
        assertThatThrownBy(
                        () ->
                                insertEvent(
                                        UUID.randomUUID(),
                                        NORTHSTAR_ID,
                                        UUID.randomUUID(),
                                        "TELEMETRY_BATCH_ACCEPTED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countEvents()).isZero();
    }

    @Test
    void rejectsAnEventThatUsesAnotherTenantsBatch() {
        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, NORTHSTAR_ID, "cross-tenant-event");

        assertThatThrownBy(
                        () ->
                                insertEvent(
                                        UUID.randomUUID(),
                                        RIVERSIDE_ID,
                                        batchId,
                                        "TELEMETRY_BATCH_ACCEPTED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countEvents()).isZero();
    }

    @Test
    void rejectsASecondEventForTheSameTenantBatch() {
        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, NORTHSTAR_ID, "duplicate-event");
        insertEvent(UUID.randomUUID(), NORTHSTAR_ID, batchId, "TELEMETRY_BATCH_ACCEPTED");

        assertThatThrownBy(
                        () ->
                                insertEvent(
                                        UUID.randomUUID(),
                                        NORTHSTAR_ID,
                                        batchId,
                                        "TELEMETRY_BATCH_ACCEPTED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countEvents()).isOne();
    }

    @Test
    void rejectsAnyOtherEventType() {
        UUID batchId = UUID.randomUUID();
        insertBatch(batchId, NORTHSTAR_ID, "invalid-event-type");

        assertThatThrownBy(
                        () ->
                                insertEvent(
                                        UUID.randomUUID(),
                                        NORTHSTAR_ID,
                                        batchId,
                                        "TELEMETRY_BATCH_REJECTED"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countEvents()).isZero();
    }

    private void insertBatch(UUID batchId, UUID organisationId, String idempotencyKey) {
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
                            :idempotencyKey,
                            :requestFingerprint,
                            1,
                            :acceptedAt
                        )
                        """)
                .param("id", batchId)
                .param("organisationId", organisationId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", "0".repeat(64))
                .param("acceptedAt", OffsetDateTime.parse("2026-08-13T12:00:00Z"))
                .update();
    }

    private void insertEvent(UUID eventId, UUID organisationId, UUID batchId, String eventType) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            :batchId,
                            :eventType,
                            :createdAt
                        )
                        """)
                .param("id", eventId)
                .param("organisationId", organisationId)
                .param("batchId", batchId)
                .param("eventType", eventType)
                .param("createdAt", OffsetDateTime.parse("2026-08-13T12:00:00Z"))
                .update();
    }

    private int countEvents() {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM telemetry_processing_event")
                .query(Integer.class)
                .single();
    }
}
