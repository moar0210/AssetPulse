package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TelemetryProcessingLifecycleMigrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BATCH_ID = UUID.fromString("50000000-0000-0000-0000-000000000008");
    private static final UUID EVENT_ID = UUID.fromString("70000000-0000-0000-0000-000000000008");
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void v8BackfillsExistingProcessingIntentAndRemainsStableAcrossRestart() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        insertExistingIntent(jdbcClient);

        Flyway.configure().dataSource(dataSource).load().migrate();
        LifecycleRow migrated = readLifecycle(jdbcClient);

        assertThat(migrated)
                .isEqualTo(
                        new LifecycleRow(
                                "PENDING",
                                0,
                                CREATED_AT,
                                CREATED_AT,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM flyway_schema_history
                                        WHERE success
                                          AND version = '8'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(readLifecycle(jdbcClient)).isEqualTo(migrated);
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM telemetry_processing_event
                                        WHERE id = :eventId
                                        """)
                                .param("eventId", EVENT_ID)
                                .query(Integer.class)
                                .single())
                .isOne();
    }

    private void insertExistingIntent(JdbcClient jdbcClient) {
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
                            :batchId,
                            :organisationId,
                            'v8-upgrade-existing-intent',
                            :requestFingerprint,
                            1,
                            :createdAt
                        )
                        """)
                .param("batchId", BATCH_ID)
                .param("organisationId", NORTHSTAR_ID)
                .param("requestFingerprint", "0".repeat(64))
                .param("createdAt", CREATED_AT.atOffset(java.time.ZoneOffset.UTC))
                .update();
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
                            :eventId,
                            :organisationId,
                            :batchId,
                            'TELEMETRY_BATCH_ACCEPTED',
                            :createdAt
                        )
                        """)
                .param("eventId", EVENT_ID)
                .param("organisationId", NORTHSTAR_ID)
                .param("batchId", BATCH_ID)
                .param("createdAt", CREATED_AT.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    private LifecycleRow readLifecycle(JdbcClient jdbcClient) {
        return jdbcClient
                .sql(
                        """
                        SELECT
                            status,
                            attempt_count,
                            next_attempt_at,
                            updated_at,
                            claim_token,
                            claim_owner,
                            lease_expires_at,
                            completed_at,
                            dead_at,
                            last_error_code,
                            last_error_message
                        FROM telemetry_processing_event
                        WHERE id = :eventId
                        """)
                .param("eventId", EVENT_ID)
                .query(TelemetryProcessingLifecycleMigrationTest::mapLifecycle)
                .single();
    }

    private static LifecycleRow mapLifecycle(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LifecycleRow(
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                readInstant(resultSet, "next_attempt_at"),
                readInstant(resultSet, "updated_at"),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getString("claim_owner"),
                readInstant(resultSet, "lease_expires_at"),
                readInstant(resultSet, "completed_at"),
                readInstant(resultSet, "dead_at"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message"));
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record LifecycleRow(
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            Instant updatedAt,
            UUID claimToken,
            String claimOwner,
            Instant leaseExpiresAt,
            Instant completedAt,
            Instant deadAt,
            String lastErrorCode,
            String lastErrorMessage) {}
}
