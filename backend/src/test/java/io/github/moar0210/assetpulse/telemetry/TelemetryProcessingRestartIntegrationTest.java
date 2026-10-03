package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moar0210.assetpulse.AssetPulseApplication;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TelemetryProcessingRestartIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T08:00:00Z");
    private static final Instant CLAIMED_AT = CREATED_AT.plusSeconds(60);

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void restartedWorkerRecoversAnAbandonedLeaseAndProducesOneAlert() {
        TelemetryProcessingClaim abandoned;
        ProcessingRow persisted;

        try (ConfigurableApplicationContext firstApplication = startApplication(false)) {
            UUID eventId = createPendingEvent(firstApplication, "restart-abandoned-lease");
            abandoned =
                    firstApplication
                            .getBean(TelemetryProcessingLifecycleService.class)
                            .claimNext("original-worker", CLAIMED_AT)
                            .orElseThrow();
            assertThat(abandoned.event().id()).isEqualTo(eventId);
            persisted = readEvent(firstApplication, eventId);
            assertThat(persisted.status()).isEqualTo("PROCESSING");
            assertThat(persisted.attemptCount()).isOne();
            assertThat(persisted.claimToken()).isEqualTo(abandoned.claimToken());
            assertThat(countAlerts(firstApplication)).isZero();
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication(true)) {
            UUID eventId = abandoned.event().id();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);
            TelemetryProcessingLifecycleService lifecycle =
                    restartedApplication.getBean(TelemetryProcessingLifecycleService.class);
            assertThat(
                            lifecycle.claimNext(
                                    "restarted-worker", abandoned.leaseExpiresAt().minusMillis(1)))
                    .isEmpty();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);

            TelemetryProcessingWorker worker =
                    restartedApplication.getBean(TelemetryProcessingWorker.class);
            worker.poll();

            ProcessingRow completed = readEvent(restartedApplication, eventId);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.attemptCount()).isEqualTo(2);
            assertThat(completed.completedAt()).isAfterOrEqualTo(abandoned.leaseExpiresAt());
            assertThat(completed.updatedAt()).isEqualTo(completed.completedAt());
            assertThat(completed.nextAttemptAt()).isNull();
            assertThat(completed.claimToken()).isNull();
            assertThat(completed.claimOwner()).isNull();
            assertThat(completed.leaseExpiresAt()).isNull();
            assertThat(completed.deadAt()).isNull();
            assertThat(completed.lastErrorCode()).isNull();
            assertThat(completed.lastErrorMessage()).isNull();
            assertSingleThresholdAlert(restartedApplication);

            assertThat(
                            restartedApplication
                                    .getBean(TelemetryProcessingExecutionService.class)
                                    .execute(
                                            abandoned,
                                            restartedApplication.getBean(
                                                    TelemetryProcessingEventHandler.class)))
                    .isFalse();
            assertThat(lifecycle.recordFailure(abandoned, Instant.now()))
                    .isEqualTo(TelemetryProcessingFailureDisposition.STALE_CLAIM);
            worker.poll();
            worker.poll();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(completed);
            assertSingleThresholdAlert(restartedApplication);
        }
    }

    @Test
    void restartedWorkerMarksAnAbandonedFinalAttemptDeadWithoutASixthAttempt() {
        TelemetryProcessingClaim abandoned;
        ProcessingRow persisted;

        try (ConfigurableApplicationContext firstApplication = startApplication(false)) {
            UUID eventId = createPendingEvent(firstApplication, "restart-final-lease");
            abandoned = claimFinalAttempt(firstApplication);
            assertThat(abandoned.event().id()).isEqualTo(eventId);
            persisted = readEvent(firstApplication, eventId);
            assertThat(persisted.status()).isEqualTo("PROCESSING");
            assertThat(persisted.attemptCount()).isEqualTo(5);
            assertThat(persisted.claimToken()).isEqualTo(abandoned.claimToken());
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication(true)) {
            UUID eventId = abandoned.event().id();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);
            TelemetryProcessingLifecycleService lifecycle =
                    restartedApplication.getBean(TelemetryProcessingLifecycleService.class);
            assertThat(
                            lifecycle.claimNext(
                                    "restarted-worker", abandoned.leaseExpiresAt().minusMillis(1)))
                    .isEmpty();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);

            TelemetryProcessingWorker worker =
                    restartedApplication.getBean(TelemetryProcessingWorker.class);
            worker.poll();

            ProcessingRow dead = readEvent(restartedApplication, eventId);
            assertDead(dead, "LEASE_EXPIRED", "Processing lease expired after the final attempt.");
            assertThat(dead.deadAt()).isAfterOrEqualTo(abandoned.leaseExpiresAt());
            assertThat(countAlerts(restartedApplication)).isZero();
            assertThat(
                            restartedApplication
                                    .getBean(TelemetryProcessingExecutionService.class)
                                    .execute(
                                            abandoned,
                                            restartedApplication.getBean(
                                                    TelemetryProcessingEventHandler.class)))
                    .isFalse();
            assertThat(lifecycle.recordFailure(abandoned, Instant.now()))
                    .isEqualTo(TelemetryProcessingFailureDisposition.STALE_CLAIM);
            worker.poll();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(dead);
            assertThat(countAlerts(restartedApplication)).isZero();
        }
    }

    @Test
    void persistedDeadStateSurvivesRestartAndWorkerPollsWithoutBeingRetried() {
        TelemetryProcessingClaim finalClaim;
        ProcessingRow persisted;

        try (ConfigurableApplicationContext firstApplication = startApplication(false)) {
            UUID eventId = createPendingEvent(firstApplication, "restart-durable-dead");
            finalClaim = claimFinalAttempt(firstApplication);
            assertThat(finalClaim.event().id()).isEqualTo(eventId);
            Instant failedAt = finalClaim.leaseExpiresAt().minusSeconds(29);
            assertThat(
                            firstApplication
                                    .getBean(TelemetryProcessingLifecycleService.class)
                                    .recordFailure(finalClaim, failedAt))
                    .isEqualTo(TelemetryProcessingFailureDisposition.DEAD);
            persisted = readEvent(firstApplication, eventId);
            assertDead(
                    persisted,
                    "PROCESSING_FAILED",
                    "Processing failed; another attempt may be scheduled.");
            assertThat(persisted.deadAt()).isEqualTo(failedAt);
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication(true)) {
            UUID eventId = finalClaim.event().id();
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);
            TelemetryProcessingWorker worker =
                    restartedApplication.getBean(TelemetryProcessingWorker.class);
            worker.poll();
            worker.poll();

            assertThat(
                            restartedApplication
                                    .getBean(TelemetryProcessingExecutionService.class)
                                    .execute(
                                            finalClaim,
                                            restartedApplication.getBean(
                                                    TelemetryProcessingEventHandler.class)))
                    .isFalse();
            assertThat(
                            restartedApplication
                                    .getBean(TelemetryProcessingLifecycleService.class)
                                    .recordFailure(finalClaim, Instant.now()))
                    .isEqualTo(TelemetryProcessingFailureDisposition.STALE_CLAIM);
            assertThat(readEvent(restartedApplication, eventId)).isEqualTo(persisted);
            assertThat(countAlerts(restartedApplication)).isZero();
        }
    }

    private ConfigurableApplicationContext startApplication(boolean processingEnabled) {
        return new SpringApplicationBuilder(AssetPulseApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                        "--spring.datasource.password=" + POSTGRESQL.getPassword(),
                        "--assetpulse.telemetry.processing.enabled=" + processingEnabled,
                        "--assetpulse.telemetry.processing.initial-delay-millis=3600000",
                        "--assetpulse.telemetry.processing.poll-delay-millis=3600000");
    }

    private UUID createPendingEvent(
            ConfigurableApplicationContext application, String idempotencyKey) {
        assertThat(application.getBeansOfType(TelemetryProcessingWorker.class)).isEmpty();
        JdbcClient jdbcClient = application.getBean(JdbcClient.class);
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();

        TelemetryBatchRequest request =
                new TelemetryBatchRequest(
                        idempotencyKey,
                        List.of(
                                new TelemetryBatchRequest.Reading(
                                        NORTHSTAR_SENSOR_ID,
                                        new BigDecimal("80.000000"),
                                        CREATED_AT)));
        TelemetryBatchRepository batches = application.getBean(TelemetryBatchRepository.class);
        // Establish only a normal pending batch at a fixed time; all later states use the
        // lifecycle.
        return application
                .getBean(TransactionTemplate.class)
                .execute(
                        transaction -> {
                            UUID batchId = UUID.randomUUID();
                            batches.tryCreate(
                                            batchId,
                                            NORTHSTAR_ID,
                                            idempotencyKey,
                                            application
                                                    .getBean(TelemetryBatchFingerprint.class)
                                                    .calculate(request),
                                            request.readings().size(),
                                            CREATED_AT)
                                    .orElseThrow();
                            batches.insertReadings(NORTHSTAR_ID, batchId, request.readings());
                            return application
                                    .getBean(TelemetryProcessingEventRepository.class)
                                    .insertBatchAccepted(NORTHSTAR_ID, batchId, CREATED_AT);
                        });
    }

    private TelemetryProcessingClaim claimFinalAttempt(ConfigurableApplicationContext application) {
        TelemetryProcessingLifecycleService lifecycle =
                application.getBean(TelemetryProcessingLifecycleService.class);
        TelemetryProcessingPolicy policy = application.getBean(TelemetryProcessingPolicy.class);
        Instant claimedAt = CLAIMED_AT;
        for (int attempt = 1; attempt < 5; attempt++) {
            TelemetryProcessingClaim claim =
                    lifecycle.claimNext("original-worker", claimedAt).orElseThrow();
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            Instant failedAt = claimedAt.plusSeconds(1);
            assertThat(lifecycle.recordFailure(claim, failedAt))
                    .isEqualTo(TelemetryProcessingFailureDisposition.RETRY_SCHEDULED);
            claimedAt = failedAt.plus(policy.retryDelay(attempt));
        }
        TelemetryProcessingClaim finalClaim =
                lifecycle.claimNext("original-worker", claimedAt).orElseThrow();
        assertThat(finalClaim.attemptCount()).isEqualTo(5);
        return finalClaim;
    }

    private void assertDead(ProcessingRow row, String errorCode, String errorMessage) {
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attemptCount()).isEqualTo(5);
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.claimToken()).isNull();
        assertThat(row.claimOwner()).isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.completedAt()).isNull();
        assertThat(row.deadAt()).isNotNull();
        assertThat(row.updatedAt()).isEqualTo(row.deadAt());
        assertThat(row.lastErrorCode()).isEqualTo(errorCode);
        assertThat(row.lastErrorMessage()).isEqualTo(errorMessage);
    }

    private int countAlerts(ConfigurableApplicationContext application) {
        return application
                .getBean(JdbcClient.class)
                .sql("SELECT COUNT(*)::integer FROM alert")
                .query(Integer.class)
                .single();
    }

    private void assertSingleThresholdAlert(ConfigurableApplicationContext application) {
        assertThat(countAlerts(application)).isOne();
        assertThat(
                        application
                                .getBean(JdbcClient.class)
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM alert
                                        WHERE organisation_id = :organisationId
                                          AND threshold_rule_id = :ruleId
                                          AND status = 'OPEN'
                                          AND occurrence_count = 1
                                        """)
                                .param("organisationId", NORTHSTAR_ID)
                                .param("ruleId", NORTHSTAR_RULE_ID)
                                .query(Integer.class)
                                .single())
                .isOne();
    }

    private ProcessingRow readEvent(ConfigurableApplicationContext application, UUID eventId) {
        return application
                .getBean(JdbcClient.class)
                .sql(
                        """
                        SELECT
                            status,
                            attempt_count,
                            next_attempt_at,
                            claim_token,
                            claim_owner,
                            lease_expires_at,
                            completed_at,
                            dead_at,
                            last_error_code,
                            last_error_message,
                            updated_at
                        FROM telemetry_processing_event
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .query(TelemetryProcessingRestartIntegrationTest::mapProcessingRow)
                .single();
    }

    private static ProcessingRow mapProcessingRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ProcessingRow(
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                readInstant(resultSet, "next_attempt_at"),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getString("claim_owner"),
                readInstant(resultSet, "lease_expires_at"),
                readInstant(resultSet, "completed_at"),
                readInstant(resultSet, "dead_at"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message"),
                readInstant(resultSet, "updated_at"));
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record ProcessingRow(
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            UUID claimToken,
            String claimOwner,
            Instant leaseExpiresAt,
            Instant completedAt,
            Instant deadAt,
            String lastErrorCode,
            String lastErrorMessage,
            Instant updatedAt) {}
}
