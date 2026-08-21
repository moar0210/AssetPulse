package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingClaim;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventHandler;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventRepository;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingExecutionService;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingFailureDisposition;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingLifecycleService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class ThresholdAlertHandlerIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SECOND_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SECOND_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final Instant FIRST_OBSERVED_AT = Instant.parse("2026-08-21T08:00:00Z");
    private static final Instant FIRST_EVENT_AT = Instant.parse("2026-08-21T09:00:00Z");
    private static final Instant FIRST_CLAIM_AT = Instant.parse("2026-08-21T10:00:00Z");

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
    @Autowired private TelemetryProcessingEventRepository eventRepository;
    @Autowired private TelemetryProcessingLifecycleService lifecycleService;
    @Autowired private TelemetryProcessingExecutionService executionService;
    @Autowired private ThresholdAlertRepository alertRepository;
    @Autowired private ThresholdAlertHandler handler;

    @BeforeEach
    void resetTelemetryAndAlerts() {
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
        jdbcClient.sql("UPDATE threshold_rule SET enabled = TRUE").update();
    }

    @Test
    void evaluatesTheExactThresholdAndIgnoresLowerReadingsAndDisabledRules() {
        EventFixture below =
                createPendingEvent(
                        "below-threshold",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("79.999999"),
                                FIRST_OBSERVED_AT));

        process(below, "worker-below", FIRST_CLAIM_AT);

        assertThat(countAlerts()).isZero();
        assertThat(readEventStatus(below.eventId())).isEqualTo("COMPLETED");

        EventFixture exact =
                createPendingEvent(
                        "exact-threshold",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("80.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(1)));

        process(exact, "worker-exact", FIRST_CLAIM_AT.plusSeconds(1));

        AlertRow alert = readOnlyAlert();
        assertThat(alert.organisationId()).isEqualTo(NORTHSTAR_ID);
        assertThat(alert.thresholdRuleId()).isEqualTo(NORTHSTAR_RULE_ID);
        assertThat(alert.status()).isEqualTo("OPEN");
        assertThat(alert.occurrenceCount()).isOne();
        assertThat(alert.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(1));
        assertThat(alert.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(1));
        assertThat(alert.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(301));

        jdbcClient
                .sql("UPDATE threshold_rule SET enabled = FALSE WHERE id = :ruleId")
                .param("ruleId", NORTHSTAR_RULE_ID)
                .update();
        EventFixture disabled =
                createPendingEvent(
                        "disabled-rule",
                        FIRST_EVENT_AT.plusSeconds(2),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("95.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(2)));

        process(disabled, "worker-disabled", FIRST_CLAIM_AT.plusSeconds(2));

        assertThat(readOnlyAlert()).isEqualTo(alert);
        assertThat(readEventStatus(disabled.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void ordersEveryEvaluationByRuleObservationAndSequenceAcrossTwoSeededRules() {
        EventFixture fixture =
                createPendingEvent(
                        "ordered-multi-rule-alert",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SECOND_SENSOR_ID,
                                new BigDecimal("84.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(20)),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("81.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(10)),
                        new ReadingFixture(
                                NORTHSTAR_SECOND_SENSOR_ID,
                                new BigDecimal("83.000000"),
                                FIRST_OBSERVED_AT),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("80.000000"),
                                FIRST_OBSERVED_AT),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("82.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(10)));

        assertThat(alertRepository.findEvaluations(NORTHSTAR_ID, fixture.batchId()))
                .extracting(
                        ThresholdAlertRepository.ThresholdEvaluation::thresholdRuleId,
                        ThresholdAlertRepository.ThresholdEvaluation::observedAt,
                        ThresholdAlertRepository.ThresholdEvaluation::value)
                .containsExactly(
                        tuple(NORTHSTAR_RULE_ID, FIRST_OBSERVED_AT, new BigDecimal("80.000000")),
                        tuple(
                                NORTHSTAR_RULE_ID,
                                FIRST_OBSERVED_AT.plusSeconds(10),
                                new BigDecimal("81.000000")),
                        tuple(
                                NORTHSTAR_RULE_ID,
                                FIRST_OBSERVED_AT.plusSeconds(10),
                                new BigDecimal("82.000000")),
                        tuple(
                                NORTHSTAR_SECOND_RULE_ID,
                                FIRST_OBSERVED_AT,
                                new BigDecimal("83.000000")),
                        tuple(
                                NORTHSTAR_SECOND_RULE_ID,
                                FIRST_OBSERVED_AT.plusSeconds(20),
                                new BigDecimal("84.000000")));

        process(fixture, "worker-ordered", FIRST_CLAIM_AT);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT(threshold_rule_id, '|', occurrence_count)
                                        FROM alert
                                        ORDER BY threshold_rule_id
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(NORTHSTAR_RULE_ID + "|3", NORTHSTAR_SECOND_RULE_ID + "|2");
        assertThat(readEventStatus(fixture.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void updatesOneFingerprintAcrossTheFixedCooldownBoundaryWithoutRegressingLateData() {
        EventFixture inside =
                createPendingEvent(
                        "inside-cooldown",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("80.000000"),
                                FIRST_OBSERVED_AT),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("81.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(299)));

        process(inside, "worker-inside", FIRST_CLAIM_AT);

        AlertRow current = readOnlyAlert();
        assertThat(current.occurrenceCount()).isEqualTo(2);
        assertThat(current.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(current.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(299));
        assertThat(current.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));

        EventFixture boundary =
                createPendingEvent(
                        "at-cooldown-boundary",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("82.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(300)));

        process(boundary, "worker-boundary", FIRST_CLAIM_AT.plusSeconds(1));

        current = readOnlyAlert();
        assertThat(current.id()).isNotNull();
        assertThat(current.occurrenceCount()).isEqualTo(3);
        assertThat(current.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));
        assertThat(current.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(600));

        EventFixture older =
                createPendingEvent(
                        "late-older-reading",
                        FIRST_EVENT_AT.plusSeconds(2),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("83.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(100)));

        process(older, "worker-older", FIRST_CLAIM_AT.plusSeconds(2));

        AlertRow afterOlder = readOnlyAlert();
        assertThat(afterOlder.id()).isEqualTo(current.id());
        assertThat(afterOlder.fingerprint()).isEqualTo(current.fingerprint());
        assertThat(afterOlder.occurrenceCount()).isEqualTo(4);
        assertThat(afterOlder.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(afterOlder.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));
        assertThat(afterOlder.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(600));
        assertThat(countAlerts()).isOne();
    }

    @Test
    void aFailedAlertEffectRollsBackBeforeRetryCreatesItOnce() {
        EventFixture fixture =
                createPendingEvent(
                        "alert-retry",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("90.000000"),
                                FIRST_OBSERVED_AT));
        TelemetryProcessingClaim first =
                lifecycleService.claimNext("worker-first", FIRST_CLAIM_AT).orElseThrow();

        assertThatThrownBy(
                        () ->
                                executionService.execute(
                                        first,
                                        event -> {
                                            handler.handle(event);
                                            throw new IllegalStateException(
                                                    "forced post-effect failure");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced post-effect failure");

        assertThat(countAlerts()).isZero();
        assertThat(readEventStatus(fixture.eventId())).isEqualTo("PROCESSING");
        Instant failedAt = FIRST_CLAIM_AT.plusSeconds(1);
        assertThat(lifecycleService.recordFailure(first, failedAt))
                .isEqualTo(TelemetryProcessingFailureDisposition.RETRY_SCHEDULED);

        TelemetryProcessingClaim retry =
                lifecycleService.claimNext("worker-retry", failedAt.plusSeconds(5)).orElseThrow();
        assertThat(retry.attemptCount()).isEqualTo(2);
        assertThat(executionService.execute(retry, handler)).isTrue();

        assertThat(readOnlyAlert().occurrenceCount()).isOne();
        assertThat(readEventStatus(fixture.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void anAbandonedClaimIsRecoveredAndProducesOneAlertEffect() {
        EventFixture fixture =
                createPendingEvent(
                        "alert-recovery",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("90.000000"),
                                FIRST_OBSERVED_AT));
        TelemetryProcessingClaim abandoned =
                lifecycleService.claimNext("worker-abandoned", FIRST_CLAIM_AT).orElseThrow();

        TelemetryProcessingClaim recovered =
                lifecycleService
                        .claimNext("worker-recovered", abandoned.leaseExpiresAt())
                        .orElseThrow();

        assertThat(recovered.event().id()).isEqualTo(fixture.eventId());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(executionService.execute(recovered, handler)).isTrue();
        assertThat(readOnlyAlert().occurrenceCount()).isOne();
        assertThat(readEventStatus(fixture.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentEventsForOneRuleCreateOneAlertWithoutLosingOccurrences() throws Exception {
        createPendingEvent(
                "concurrent-alert-a",
                FIRST_EVENT_AT,
                new ReadingFixture(
                        NORTHSTAR_SENSOR_ID, new BigDecimal("90.000000"), FIRST_OBSERVED_AT));
        createPendingEvent(
                "concurrent-alert-b",
                FIRST_EVENT_AT.plusSeconds(1),
                new ReadingFixture(
                        NORTHSTAR_SENSOR_ID,
                        new BigDecimal("91.000000"),
                        FIRST_OBSERVED_AT.plusSeconds(1)));
        TelemetryProcessingClaim first =
                lifecycleService.claimNext("worker-a", FIRST_CLAIM_AT).orElseThrow();
        TelemetryProcessingClaim second =
                lifecycleService.claimNext("worker-b", FIRST_CLAIM_AT).orElseThrow();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch handlersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        TelemetryProcessingEventHandler concurrentHandler =
                event -> {
                    handlersReady.countDown();
                    await(start);
                    handler.handle(event);
                };

        try {
            Future<Boolean> firstResult =
                    executor.submit(() -> executionService.execute(first, concurrentHandler));
            Future<Boolean> secondResult =
                    executor.submit(() -> executionService.execute(second, concurrentHandler));
            assertThat(handlersReady.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            assertThat(firstResult.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        AlertRow alert = readOnlyAlert();
        assertThat(alert.occurrenceCount()).isEqualTo(2);
        assertThat(alert.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(alert.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(1));
        assertThat(countAlerts()).isOne();
        assertThat(countEventsWithStatus("COMPLETED")).isEqualTo(2);
    }

    @Test
    void databaseRejectsForeignRuleOwnershipAndMalformedFingerprints() {
        assertThatThrownBy(
                        () -> insertAlertDirectly(RIVERSIDE_ID, NORTHSTAR_RULE_ID, "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertAlertDirectly(
                                        NORTHSTAR_ID, NORTHSTAR_RULE_ID, "not-a-valid-fingerprint"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countAlerts()).isZero();
        insertAlertDirectly(NORTHSTAR_ID, NORTHSTAR_RULE_ID, "0".repeat(64));

        assertThatThrownBy(() -> jdbcClient.sql("UPDATE alert SET occurrence_count = 0").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () -> jdbcClient.sql("UPDATE alert SET status = 'ACKNOWLEDGED'").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE alert
                                                SET cooldown_until = last_occurred_at - INTERVAL '1 second'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("DELETE FROM alert").update();
        assertThat(countAlerts()).isZero();
    }

    private void process(EventFixture fixture, String owner, Instant claimedAt) {
        TelemetryProcessingClaim claim = lifecycleService.claimNext(owner, claimedAt).orElseThrow();
        assertThat(claim.event().id()).isEqualTo(fixture.eventId());
        assertThat(executionService.execute(claim, handler)).isTrue();
    }

    private EventFixture createPendingEvent(
            String idempotencyKey, Instant eventAt, ReadingFixture... readings) {
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
                            :idempotencyKey,
                            :requestFingerprint,
                            :readingCount,
                            :acceptedAt
                        )
                        """)
                .param("id", batchId)
                .param("organisationId", NORTHSTAR_ID)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", "0".repeat(64))
                .param("readingCount", readings.length)
                .param("acceptedAt", eventAt.atOffset(ZoneOffset.UTC))
                .update();
        for (int sequence = 0; sequence < readings.length; sequence++) {
            ReadingFixture reading = readings[sequence];
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
                                :sequenceNumber,
                                :sensorId,
                                :value,
                                :observedAt
                            )
                            """)
                    .param("id", UUID.randomUUID())
                    .param("organisationId", NORTHSTAR_ID)
                    .param("batchId", batchId)
                    .param("sequenceNumber", sequence)
                    .param("sensorId", reading.sensorId())
                    .param("value", reading.value())
                    .param("observedAt", reading.observedAt().atOffset(ZoneOffset.UTC))
                    .update();
        }
        eventRepository.insertBatchAccepted(NORTHSTAR_ID, batchId, eventAt);
        UUID eventId =
                jdbcClient
                        .sql(
                                """
                                SELECT id
                                FROM telemetry_processing_event
                                WHERE organisation_id = :organisationId
                                  AND telemetry_batch_id = :batchId
                                """)
                        .param("organisationId", NORTHSTAR_ID)
                        .param("batchId", batchId)
                        .query(UUID.class)
                        .single();
        return new EventFixture(eventId, batchId);
    }

    private void insertAlertDirectly(
            UUID organisationId, UUID thresholdRuleId, String fingerprint) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
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
                            :occurredAt,
                            :occurredAt,
                            :cooldownUntil,
                            :createdAt,
                            :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("organisationId", organisationId)
                .param("thresholdRuleId", thresholdRuleId)
                .param("fingerprint", fingerprint)
                .param("occurredAt", FIRST_OBSERVED_AT.atOffset(ZoneOffset.UTC))
                .param("cooldownUntil", FIRST_OBSERVED_AT.plusSeconds(300).atOffset(ZoneOffset.UTC))
                .param("createdAt", FIRST_EVENT_AT.atOffset(ZoneOffset.UTC))
                .update();
    }

    private AlertRow readOnlyAlert() {
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
                        """)
                .query(ThresholdAlertHandlerIntegrationTest::mapAlert)
                .single();
    }

    private static AlertRow mapAlert(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AlertRow(
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
                readInstant(resultSet, "updated_at"));
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private int countAlerts() {
        return jdbcClient.sql("SELECT COUNT(*)::integer FROM alert").query(Integer.class).single();
    }

    private int countEventsWithStatus(String status) {
        return jdbcClient
                .sql(
                        """
                        SELECT COUNT(*)::integer
                        FROM telemetry_processing_event
                        WHERE status = :status
                        """)
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private String readEventStatus(UUID eventId) {
        return jdbcClient
                .sql("SELECT status FROM telemetry_processing_event WHERE id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent alert handlers");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for alert handler", interrupted);
        }
    }

    private record ReadingFixture(UUID sensorId, BigDecimal value, Instant observedAt) {}

    private record EventFixture(UUID eventId, UUID batchId) {}

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
