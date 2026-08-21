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
    private static final UUID ALERT_ID = UUID.fromString("80000000-0000-0000-0000-000000000008");
    private static final UUID THRESHOLD_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String ALERT_FINGERPRINT =
            "8888888888888888888888888888888888888888888888888888888888888888";
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T08:00:00Z");
    private static final Instant ALERT_FIRST_OCCURRED_AT = Instant.parse("2026-08-17T08:15:00Z");
    private static final Instant ALERT_LAST_OCCURRED_AT = Instant.parse("2026-08-17T08:16:00Z");
    private static final Instant ALERT_COOLDOWN_UNTIL = Instant.parse("2026-08-17T08:20:00Z");
    private static final Instant ALERT_CREATED_AT = Instant.parse("2026-08-17T08:30:00Z");
    private static final Instant ALERT_UPDATED_AT = Instant.parse("2026-08-17T08:31:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void laterMigrationsPreserveV8ProcessingStateAndAnExistingV9AlertAcrossRestart() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        insertExistingIntent(jdbcClient);

        Flyway.configure().dataSource(dataSource).target("9").load().migrate();
        insertExistingAlert(jdbcClient);
        AlertRow existingAlert = readAlert(jdbcClient);

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
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM flyway_schema_history
                                        WHERE success
                                          AND version = '9'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM flyway_schema_history
                                        WHERE success
                                          AND version = '10'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();
        assertThat(existingAlert)
                .isEqualTo(
                        new AlertRow(
                                ALERT_ID,
                                NORTHSTAR_ID,
                                THRESHOLD_RULE_ID,
                                ALERT_FINGERPRINT,
                                "OPEN",
                                3,
                                ALERT_FIRST_OCCURRED_AT,
                                ALERT_LAST_OCCURRED_AT,
                                ALERT_COOLDOWN_UNTIL,
                                ALERT_CREATED_AT,
                                ALERT_UPDATED_AT));
        assertThat(readAlert(jdbcClient)).isEqualTo(existingAlert);
        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*)::integer FROM alert_status_history")
                                .query(Integer.class)
                                .single())
                .isZero();

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(readLifecycle(jdbcClient)).isEqualTo(migrated);
        assertThat(readAlert(jdbcClient)).isEqualTo(existingAlert);
        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*)::integer FROM alert_status_history")
                                .query(Integer.class)
                                .single())
                .isZero();
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

    private void insertExistingAlert(JdbcClient jdbcClient) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            occurrence_count,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            :thresholdRuleId,
                            :fingerprint,
                            3,
                            :firstOccurredAt,
                            :lastOccurredAt,
                            :cooldownUntil,
                            :createdAt,
                            :updatedAt
                        )
                        """)
                .param("id", ALERT_ID)
                .param("organisationId", NORTHSTAR_ID)
                .param("thresholdRuleId", THRESHOLD_RULE_ID)
                .param("fingerprint", ALERT_FINGERPRINT)
                .param(
                        "firstOccurredAt",
                        ALERT_FIRST_OCCURRED_AT.atOffset(java.time.ZoneOffset.UTC))
                .param("lastOccurredAt", ALERT_LAST_OCCURRED_AT.atOffset(java.time.ZoneOffset.UTC))
                .param("cooldownUntil", ALERT_COOLDOWN_UNTIL.atOffset(java.time.ZoneOffset.UTC))
                .param("createdAt", ALERT_CREATED_AT.atOffset(java.time.ZoneOffset.UTC))
                .param("updatedAt", ALERT_UPDATED_AT.atOffset(java.time.ZoneOffset.UTC))
                .update();
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

    private AlertRow readAlert(JdbcClient jdbcClient) {
        return jdbcClient
                .sql(
                        """
                        SELECT
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            status,
                            occurrence_count,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        FROM alert
                        WHERE id = :alertId
                        """)
                .param("alertId", ALERT_ID)
                .query(
                        (resultSet, rowNumber) ->
                                new AlertRow(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getObject("organisation_id", UUID.class),
                                        resultSet.getObject("threshold_rule_id", UUID.class),
                                        resultSet.getString("fingerprint"),
                                        resultSet.getString("status"),
                                        resultSet.getLong("occurrence_count"),
                                        readInstant(resultSet, "first_occurred_at"),
                                        readInstant(resultSet, "last_occurred_at"),
                                        readInstant(resultSet, "cooldown_until"),
                                        readInstant(resultSet, "created_at"),
                                        readInstant(resultSet, "updated_at")))
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

    private record AlertRow(
            UUID id,
            UUID organisationId,
            UUID thresholdRuleId,
            String fingerprint,
            String status,
            long occurrenceCount,
            Instant firstOccurredAt,
            Instant lastOccurredAt,
            Instant cooldownUntil,
            Instant createdAt,
            Instant updatedAt) {}
}
