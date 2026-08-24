package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingClaim;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventHandler;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventRepository;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingExecutionService;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingFailureDisposition;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingLifecycleService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ThresholdAlertHandlerIntegrationTest {

    private static final String ALERT_STREAM_PATH = "/api/v1/alerts/stream";
    private static final String READY_EVENT = "event:ready\ndata:{}\n\n";

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
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
    @Autowired private AlertCommandService commandService;
    @Autowired private AlertStreamService streamService;
    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetTelemetryAndAlerts() {
        streamService.closeAll();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
        jdbcClient.sql("UPDATE threshold_rule SET enabled = TRUE").update();
    }

    @Test
    void thresholdInvalidationIsTenantScopedAfterCommitAndSilentBelowThresholdAndOnRollback()
            throws Exception {
        MvcResult northstarStream = openStream(NORTHSTAR_ID);
        MvcResult riversideStream = openStream(RIVERSIDE_ID);

        try {
            EventFixture below =
                    createPendingEvent(
                            "stream-below-threshold",
                            FIRST_EVENT_AT,
                            new ReadingFixture(
                                    NORTHSTAR_SENSOR_ID,
                                    new BigDecimal("79.999999"),
                                    FIRST_OBSERVED_AT));
            process(below, "worker-stream-below", FIRST_CLAIM_AT);

            assertStreamRemains(northstarStream, READY_EVENT);
            assertStreamRemains(riversideStream, READY_EVENT);

            EventFixture committed =
                    createPendingEvent(
                            "stream-committed-threshold",
                            FIRST_EVENT_AT.plusSeconds(1),
                            new ReadingFixture(
                                    NORTHSTAR_SENSOR_ID,
                                    new BigDecimal("90.000000"),
                                    FIRST_OBSERVED_AT.plusSeconds(1)));
            TelemetryProcessingClaim committedClaim =
                    lifecycleService
                            .claimNext("worker-stream-commit", FIRST_CLAIM_AT.plusSeconds(1))
                            .orElseThrow();
            assertThat(committedClaim.event().id()).isEqualTo(committed.eventId());

            assertThat(
                            executionService.execute(
                                    committedClaim,
                                    event -> {
                                        handler.handle(event);
                                        assertThat(streamContent(northstarStream))
                                                .isEqualTo(READY_EVENT);
                                    }))
                    .isTrue();

            AlertRow committedAlert = readOnlyAlert();
            String committedChange =
                    "event:alert-changed\ndata:{\"alertId\":\""
                            + committedAlert.id()
                            + "\",\"changeType\":\"OCCURRENCE_RECORDED\"}\n\n";
            awaitStreamContains(northstarStream, committedChange);
            assertThat(streamContent(northstarStream))
                    .isEqualTo(READY_EVENT + committedChange)
                    .doesNotContain("organisationId")
                    .doesNotContain("fingerprint")
                    .doesNotContain("telemetry");
            assertStreamRemains(riversideStream, READY_EVENT);

            EventFixture rolledBack =
                    createPendingEvent(
                            "stream-rolled-back-threshold",
                            FIRST_EVENT_AT.plusSeconds(2),
                            new ReadingFixture(
                                    NORTHSTAR_SENSOR_ID,
                                    new BigDecimal("91.000000"),
                                    FIRST_OBSERVED_AT.plusSeconds(2)));
            TelemetryProcessingClaim rolledBackClaim =
                    lifecycleService
                            .claimNext("worker-stream-rollback", FIRST_CLAIM_AT.plusSeconds(2))
                            .orElseThrow();
            assertThat(rolledBackClaim.event().id()).isEqualTo(rolledBack.eventId());
            String contentBeforeRollback = streamContent(northstarStream);

            assertThatThrownBy(
                            () ->
                                    executionService.execute(
                                            rolledBackClaim,
                                            event -> {
                                                handler.handle(event);
                                                assertThat(streamContent(northstarStream))
                                                        .isEqualTo(contentBeforeRollback);
                                                throw new IllegalStateException(
                                                        "forced post-effect rollback");
                                            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("forced post-effect rollback");

            assertStreamRemains(northstarStream, contentBeforeRollback);
            assertStreamRemains(riversideStream, READY_EVENT);
            assertThat(readOnlyAlert().occurrenceCount()).isOne();
            assertThat(readEventStatus(rolledBack.eventId())).isEqualTo("PROCESSING");
        } finally {
            streamService.closeAll();
        }
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
        CountDownLatch firstOccurrenceApplied = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondHandlerEntered = new CountDownLatch(1);

        try {
            Future<Boolean> firstResult =
                    executor.submit(
                            () ->
                                    executionService.execute(
                                            first,
                                            event -> {
                                                handler.handle(event);
                                                firstOccurrenceApplied.countDown();
                                                await(allowFirstCommit);
                                            }));
            assertThat(firstOccurrenceApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> secondResult =
                    executor.submit(
                            () ->
                                    executionService.execute(
                                            second,
                                            event -> {
                                                secondHandlerEntered.countDown();
                                                handler.handle(event);
                                            }));
            assertThat(secondHandlerEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(waitForBlockedThresholdRuleLock()).isTrue();

            allowFirstCommit.countDown();

            assertThat(firstResult.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
        }

        AlertRow alert = readOnlyAlert();
        assertThat(alert.occurrenceCount()).isEqualTo(2);
        assertThat(alert.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(alert.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(1));
        assertThat(countAlerts()).isOne();
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(countEventsWithStatus("COMPLETED")).isEqualTo(2);
    }

    @Test
    void breachBeforeResolvedCooldownUpdatesOnlyTheResolvedIncident() {
        AlertRow resolved =
                createResolvedAlert("resolved-inside-cooldown", "worker-resolved-inside");
        EventFixture inside =
                createPendingEvent(
                        "breach-inside-resolved-cooldown",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("91.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(299)));

        process(inside, "worker-inside-resolved-cooldown", FIRST_CLAIM_AT.plusSeconds(1));

        AlertRow resolvedAfter = readAlertWithStatus("RESOLVED");
        assertThat(resolvedAfter.id()).isEqualTo(resolved.id());
        assertThat(resolvedAfter.occurrenceCount()).isEqualTo(2);
        assertThat(resolvedAfter.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolvedAfter.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(299));
        assertThat(resolvedAfter.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));
        assertThat(countAlerts()).isOne();
        assertThat(countAlertsWithStatus("OPEN")).isZero();
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(countAlertHistory()).isZero();
    }

    @Test
    void breachAtResolvedCooldownCreatesOneNewOpenIncident() {
        AlertRow resolved =
                createResolvedAlert("resolved-at-cooldown", "worker-resolved-at-boundary");
        EventFixture boundary =
                createPendingEvent(
                        "breach-at-resolved-cooldown",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("91.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(300)));

        process(boundary, "worker-at-resolved-cooldown", FIRST_CLAIM_AT.plusSeconds(1));

        AlertRow resolvedAfter = readAlertWithStatus("RESOLVED");
        assertThat(resolvedAfter.id()).isEqualTo(resolved.id());
        assertThat(resolvedAfter.occurrenceCount()).isOne();
        assertThat(resolvedAfter.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolvedAfter.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));

        AlertRow opened = readAlertWithStatus("OPEN");
        assertThat(opened.id()).isNotEqualTo(resolved.id());
        assertThat(opened.fingerprint()).isEqualTo(resolved.fingerprint());
        assertThat(opened.occurrenceCount()).isOne();
        assertThat(opened.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));
        assertThat(opened.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));
        assertThat(opened.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(600));
        assertThat(countAlerts()).isEqualTo(2);
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(countAlertHistory()).isZero();
    }

    @Test
    void acknowledgedAlertKeepsAccumulatingOccurrencesAcrossCooldowns() {
        EventFixture initial =
                createPendingEvent(
                        "acknowledged-active-alert",
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("90.000000"),
                                FIRST_OBSERVED_AT));
        process(initial, "worker-acknowledged-initial", FIRST_CLAIM_AT);
        AlertRow acknowledged = readOnlyAlert();
        assertThat(
                        jdbcClient
                                .sql("UPDATE alert SET status = 'ACKNOWLEDGED' WHERE id = :alertId")
                                .param("alertId", acknowledged.id())
                                .update())
                .isOne();
        EventFixture later =
                createPendingEvent(
                        "acknowledged-active-later-breach",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("91.000000"),
                                FIRST_OBSERVED_AT.plusSeconds(600)));

        process(later, "worker-acknowledged-later", FIRST_CLAIM_AT.plusSeconds(1));

        AlertRow acknowledgedAfter = readAlertWithStatus("ACKNOWLEDGED");
        assertThat(acknowledgedAfter.id()).isEqualTo(acknowledged.id());
        assertThat(acknowledgedAfter.occurrenceCount()).isEqualTo(2);
        assertThat(acknowledgedAfter.lastOccurredAt())
                .isEqualTo(FIRST_OBSERVED_AT.plusSeconds(600));
        assertThat(acknowledgedAfter.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(900));
        assertThat(countAlerts()).isOne();
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(countAlertHistory()).isZero();
    }

    @Test
    void occurrenceThatLocksBeforeResolutionStaysOnResolvedIncident() throws Exception {
        AlertRow acknowledged =
                createAcknowledgedAlert("occurrence-before-resolution", "worker-occurrence-first");
        Instant boundary = FIRST_OBSERVED_AT.plusSeconds(300);
        EventFixture later =
                createPendingEvent(
                        "boundary-occurrence-before-resolution",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID, new BigDecimal("91.000000"), boundary));
        TelemetryProcessingClaim claim =
                lifecycleService
                        .claimNext(
                                "worker-boundary-occurrence-first", FIRST_CLAIM_AT.plusSeconds(1))
                        .orElseThrow();
        assertThat(claim.event().id()).isEqualTo(later.eventId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch occurrenceApplied = new CountDownLatch(1);
        CountDownLatch allowOccurrenceCommit = new CountDownLatch(1);
        try {
            Future<Boolean> occurrenceResult =
                    executor.submit(
                            () ->
                                    executionService.execute(
                                            claim,
                                            event -> {
                                                handler.handle(event);
                                                occurrenceApplied.countDown();
                                                await(allowOccurrenceCommit);
                                            }));
            assertThat(occurrenceApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<AlertDetailResponse> resolutionResult =
                    executor.submit(
                            () ->
                                    commandService.resolve(
                                            NORTHSTAR_ID, NORTHSTAR_ADMIN_ID, acknowledged.id()));

            assertThat(waitForBlockedAlertTransition()).isTrue();
            allowOccurrenceCommit.countDown();

            assertThat(occurrenceResult.get(10, TimeUnit.SECONDS)).isTrue();
            AlertDetailResponse resolvedResponse = resolutionResult.get(10, TimeUnit.SECONDS);
            assertForwardOnlyHistory(resolvedResponse, acknowledged.id());
        } finally {
            allowOccurrenceCommit.countDown();
            executor.shutdownNow();
        }

        AlertRow resolved = readAlertWithStatus("RESOLVED");
        assertThat(resolved.id()).isEqualTo(acknowledged.id());
        assertThat(resolved.fingerprint()).isEqualTo(acknowledged.fingerprint());
        assertThat(resolved.occurrenceCount()).isEqualTo(2);
        assertThat(resolved.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolved.lastOccurredAt()).isEqualTo(boundary);
        assertThat(resolved.cooldownUntil()).isEqualTo(boundary.plusSeconds(300));
        assertThat(countAlerts()).isOne();
        assertThat(countActiveAlerts()).isZero();
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(countAlertHistory(resolved.id())).isEqualTo(2);
        assertThat(readEventStatus(later.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void resolutionThatLocksBeforeBoundaryOccurrenceOpensNewIncident() throws Exception {
        AlertRow acknowledged =
                createAcknowledgedAlert("resolution-before-occurrence", "worker-resolution-first");
        Instant boundary = FIRST_OBSERVED_AT.plusSeconds(300);
        EventFixture later =
                createPendingEvent(
                        "boundary-occurrence-after-resolution",
                        FIRST_EVENT_AT.plusSeconds(1),
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID, new BigDecimal("91.000000"), boundary));
        TelemetryProcessingClaim claim =
                lifecycleService
                        .claimNext(
                                "worker-boundary-resolution-first", FIRST_CLAIM_AT.plusSeconds(1))
                        .orElseThrow();
        assertThat(claim.event().id()).isEqualTo(later.eventId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch resolutionApplied = new CountDownLatch(1);
        CountDownLatch allowResolutionCommit = new CountDownLatch(1);
        CountDownLatch occurrenceEntered = new CountDownLatch(1);
        try {
            Future<AlertDetailResponse> resolutionResult =
                    executor.submit(
                            () ->
                                    transactionTemplate.execute(
                                            transactionStatus -> {
                                                AlertDetailResponse response =
                                                        commandService.resolve(
                                                                NORTHSTAR_ID,
                                                                NORTHSTAR_ADMIN_ID,
                                                                acknowledged.id());
                                                resolutionApplied.countDown();
                                                await(allowResolutionCommit);
                                                return response;
                                            }));
            assertThat(resolutionApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> occurrenceResult =
                    executor.submit(
                            () ->
                                    executionService.execute(
                                            claim,
                                            event -> {
                                                occurrenceEntered.countDown();
                                                handler.handle(event);
                                            }));
            assertThat(occurrenceEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(waitForBlockedAlertOccurrence()).isTrue();
            allowResolutionCommit.countDown();

            AlertDetailResponse resolvedResponse = resolutionResult.get(10, TimeUnit.SECONDS);
            assertForwardOnlyHistory(resolvedResponse, acknowledged.id());
            assertThat(occurrenceResult.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowResolutionCommit.countDown();
            executor.shutdownNow();
        }

        AlertRow resolved = readAlertWithStatus("RESOLVED");
        assertThat(resolved.id()).isEqualTo(acknowledged.id());
        assertThat(resolved.fingerprint()).isEqualTo(acknowledged.fingerprint());
        assertThat(resolved.occurrenceCount()).isOne();
        assertThat(resolved.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolved.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolved.cooldownUntil()).isEqualTo(boundary);
        assertThat(countAlertHistory(resolved.id())).isEqualTo(2);

        AlertRow opened = readAlertWithStatus("OPEN");
        assertThat(opened.id()).isNotEqualTo(resolved.id());
        assertThat(opened.fingerprint()).isEqualTo(resolved.fingerprint());
        assertThat(opened.occurrenceCount()).isOne();
        assertThat(opened.firstOccurredAt()).isEqualTo(boundary);
        assertThat(opened.lastOccurredAt()).isEqualTo(boundary);
        assertThat(opened.cooldownUntil()).isEqualTo(boundary.plusSeconds(300));
        assertThat(countAlertHistory(opened.id())).isZero();
        assertThat(countAlerts()).isEqualTo(2);
        assertThat(countActiveAlerts()).isOne();
        assertThat(totalOccurrenceCount()).isEqualTo(2);
        assertThat(readEventStatus(later.eventId())).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentBreachesAfterResolutionCreateOneNewOpenAlert() throws Exception {
        AlertRow resolved = createResolvedAlert("resolved-alert", "worker-initial");

        createPendingEvent(
                "post-resolution-alert-a",
                FIRST_EVENT_AT.plusSeconds(10),
                new ReadingFixture(
                        NORTHSTAR_SENSOR_ID,
                        new BigDecimal("91.000000"),
                        FIRST_OBSERVED_AT.plusSeconds(600)));
        createPendingEvent(
                "post-resolution-alert-b",
                FIRST_EVENT_AT.plusSeconds(11),
                new ReadingFixture(
                        NORTHSTAR_SENSOR_ID,
                        new BigDecimal("92.000000"),
                        FIRST_OBSERVED_AT.plusSeconds(601)));
        TelemetryProcessingClaim first =
                lifecycleService
                        .claimNext("worker-reopen-a", FIRST_CLAIM_AT.plusSeconds(10))
                        .orElseThrow();
        TelemetryProcessingClaim second =
                lifecycleService
                        .claimNext("worker-reopen-b", FIRST_CLAIM_AT.plusSeconds(10))
                        .orElseThrow();
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

        AlertRow resolvedAfter = readAlertWithStatus("RESOLVED");
        assertThat(resolvedAfter.id()).isEqualTo(resolved.id());
        assertThat(resolvedAfter.occurrenceCount()).isOne();
        assertThat(resolvedAfter.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolvedAfter.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT);
        assertThat(resolvedAfter.cooldownUntil()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(300));

        AlertRow reopened = readAlertWithStatus("OPEN");
        assertThat(reopened.id()).isNotEqualTo(resolved.id());
        assertThat(reopened.fingerprint()).isEqualTo(resolved.fingerprint());
        assertThat(reopened.occurrenceCount()).isEqualTo(2);
        assertThat(reopened.firstOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(600));
        assertThat(reopened.lastOccurredAt()).isEqualTo(FIRST_OBSERVED_AT.plusSeconds(601));
        assertThat(countAlerts()).isEqualTo(2);
        assertThat(totalOccurrenceCount()).isEqualTo(3);
        assertThat(countAlertHistory()).isZero();
        assertThat(countEventsWithStatus("COMPLETED")).isEqualTo(3);
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
        assertThat(jdbcClient.sql("UPDATE alert SET status = 'ACKNOWLEDGED'").update()).isOne();
        assertThat(jdbcClient.sql("UPDATE alert SET status = 'RESOLVED'").update()).isOne();
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE alert SET status = 'UNKNOWN'").update())
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

    private MvcResult openStream(UUID organisationId) throws Exception {
        AuthenticatedActor actor =
                new AuthenticatedActor(
                        UUID.randomUUID(),
                        "stream-viewer@example.test",
                        "Stream Viewer",
                        "unused-password",
                        organisationId,
                        "stream-organisation",
                        "Stream Organisation",
                        "VIEWER",
                        "Viewer");
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        actor, null, actor.getAuthorities());
        MvcResult result =
                mockMvc.perform(
                                get(ALERT_STREAM_PATH)
                                        .with(authentication(authentication))
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertThat(streamContent(result)).isEqualTo(READY_EVENT);
        return result;
    }

    private void awaitStreamContains(MvcResult result, String expected)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (streamContent(result).contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(streamContent(result)).contains(expected);
    }

    private void assertStreamRemains(MvcResult result, String expected)
            throws InterruptedException {
        for (int attempt = 0; attempt < 25; attempt++) {
            assertThat(streamContent(result)).isEqualTo(expected);
            Thread.sleep(10);
        }
    }

    private String streamContent(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void process(EventFixture fixture, String owner, Instant claimedAt) {
        TelemetryProcessingClaim claim = lifecycleService.claimNext(owner, claimedAt).orElseThrow();
        assertThat(claim.event().id()).isEqualTo(fixture.eventId());
        assertThat(executionService.execute(claim, handler)).isTrue();
    }

    private AlertRow createAcknowledgedAlert(String idempotencyKey, String owner) {
        EventFixture initial =
                createPendingEvent(
                        idempotencyKey,
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("90.000000"),
                                FIRST_OBSERVED_AT));
        process(initial, owner, FIRST_CLAIM_AT);
        AlertRow opened = readOnlyAlert();
        AlertDetailResponse acknowledged =
                commandService.acknowledge(NORTHSTAR_ID, NORTHSTAR_ADMIN_ID, opened.id());
        assertThat(acknowledged.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        return readOnlyAlert();
    }

    private AlertRow createResolvedAlert(String idempotencyKey, String owner) {
        EventFixture initial =
                createPendingEvent(
                        idempotencyKey,
                        FIRST_EVENT_AT,
                        new ReadingFixture(
                                NORTHSTAR_SENSOR_ID,
                                new BigDecimal("90.000000"),
                                FIRST_OBSERVED_AT));
        process(initial, owner, FIRST_CLAIM_AT);
        AlertRow resolved = readOnlyAlert();
        assertThat(
                        jdbcClient
                                .sql("UPDATE alert SET status = 'RESOLVED' WHERE id = :alertId")
                                .param("alertId", resolved.id())
                                .update())
                .isOne();
        return resolved;
    }

    private static void assertForwardOnlyHistory(
            AlertDetailResponse resolved, UUID expectedAlertId) {
        assertThat(resolved.id()).isEqualTo(expectedAlertId);
        assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.history())
                .extracting(
                        AlertHistoryResponse::sequenceNumber,
                        AlertHistoryResponse::fromStatus,
                        AlertHistoryResponse::toStatus)
                .containsExactly(
                        tuple(1, AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED),
                        tuple(2, AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED));
        assertThat(resolved.history())
                .extracting(history -> history.actor().id())
                .containsExactly(NORTHSTAR_ADMIN_ID, NORTHSTAR_ADMIN_ID);
        assertThat(resolved.history().get(1).transitionedAt())
                .isAfterOrEqualTo(resolved.history().get(0).transitionedAt());
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

    private AlertRow readAlertWithStatus(String status) {
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
                        WHERE status = :status
                        """)
                .param("status", status)
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

    private int countAlertsWithStatus(String status) {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM alert WHERE status = :status")
                .param("status", status)
                .query(Integer.class)
                .single();
    }

    private int countActiveAlerts() {
        return jdbcClient
                .sql(
                        """
                        SELECT COUNT(*)::integer
                        FROM alert
                        WHERE status IN ('OPEN', 'ACKNOWLEDGED')
                        """)
                .query(Integer.class)
                .single();
    }

    private long totalOccurrenceCount() {
        return jdbcClient
                .sql("SELECT COALESCE(SUM(occurrence_count), 0)::bigint FROM alert")
                .query(Long.class)
                .single();
    }

    private int countAlertHistory() {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM alert_status_history")
                .query(Integer.class)
                .single();
    }

    private int countAlertHistory(UUID alertId) {
        return jdbcClient
                .sql(
                        """
                        SELECT COUNT(*)::integer
                        FROM alert_status_history
                        WHERE organisation_id = :organisationId
                          AND alert_id = :alertId
                        """)
                .param("organisationId", NORTHSTAR_ID)
                .param("alertId", alertId)
                .query(Integer.class)
                .single();
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

    private boolean waitForBlockedAlertTransition() throws InterruptedException {
        return waitForBlockedAlertQuery("%UPDATE alert%", "%SET status =%");
    }

    private boolean waitForBlockedThresholdRuleLock() throws InterruptedException {
        return waitForBlockedAlertQuery("%FROM threshold_rule%", "%FOR UPDATE%");
    }

    private boolean waitForBlockedAlertOccurrence() throws InterruptedException {
        return waitForBlockedAlertQuery("%WITH locked_alert AS MATERIALIZED%", null);
    }

    private boolean waitForBlockedAlertQuery(String queryPattern, String secondQueryPattern)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            String secondPredicate =
                    secondQueryPattern == null ? "" : " AND query LIKE :secondQueryPattern";
            JdbcClient.StatementSpec statement =
                    jdbcClient
                            .sql(
                                    """
                                    SELECT COUNT(*)::integer
                                    FROM pg_stat_activity
                                    WHERE pid <> pg_backend_pid()
                                      AND wait_event_type = 'Lock'
                                      AND query LIKE :queryPattern
                                    """
                                            + secondPredicate)
                            .param("queryPattern", queryPattern);
            if (secondQueryPattern != null) {
                statement = statement.param("secondQueryPattern", secondQueryPattern);
            }
            if (statement.query(Integer.class).single() > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
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
