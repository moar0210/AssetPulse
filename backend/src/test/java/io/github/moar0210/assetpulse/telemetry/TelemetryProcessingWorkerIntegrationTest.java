package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class TelemetryProcessingWorkerIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-17T08:00:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-08-17T09:00:00Z");

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
    @Autowired private TelemetryProcessingLifecycleService lifecycleService;
    @Autowired private TelemetryProcessingExecutionService executionService;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearTelemetry() {
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void claimsAnInitialPendingEventWithItsFirstFencedLease() {
        EventFixture fixture = createPendingEvent("initial-claim");

        ProcessingRow pending = readEvent(fixture.eventId());
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.nextAttemptAt()).isEqualTo(CREATED_AT);
        assertThat(pending.claimToken()).isNull();
        assertThat(pending.claimOwner()).isNull();
        assertThat(pending.leaseExpiresAt()).isNull();
        assertThat(pending.completedAt()).isNull();
        assertThat(pending.deadAt()).isNull();
        assertThat(pending.lastErrorCode()).isNull();
        assertThat(pending.lastErrorMessage()).isNull();
        assertThat(pending.updatedAt()).isEqualTo(CREATED_AT);

        TelemetryProcessingClaim claim =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();

        assertThat(applicationContext.getBeansOfType(TelemetryProcessingWorker.class)).isEmpty();
        assertThat(claim.event().id()).isEqualTo(fixture.eventId());
        assertThat(claim.event().organisationId()).isEqualTo(NORTHSTAR_ID);
        assertThat(claim.event().telemetryBatchId()).isEqualTo(fixture.batchId());
        assertThat(claim.event().eventType()).isEqualTo("TELEMETRY_BATCH_ACCEPTED");
        assertThat(claim.event().createdAt()).isEqualTo(CREATED_AT);
        assertThat(claim.claimToken()).isNotNull();
        assertThat(claim.attemptCount()).isOne();
        assertThat(claim.leaseExpiresAt()).isEqualTo(CLAIMED_AT.plusSeconds(30));

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("PROCESSING");
        assertThat(row.attemptCount()).isOne();
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.claimToken()).isEqualTo(claim.claimToken());
        assertThat(row.claimOwner()).isEqualTo("worker-a");
        assertThat(row.leaseExpiresAt()).isEqualTo(CLAIMED_AT.plusSeconds(30));
        assertThat(row.updatedAt()).isEqualTo(CLAIMED_AT);
    }

    @Test
    void concurrentWorkersCanClaimAnEventOnlyOnce() throws Exception {
        EventFixture fixture = createPendingEvent("concurrent-claim");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Optional<TelemetryProcessingClaim>> first =
                    submitClaim(executor, workersReady, start, "worker-a");
            Future<Optional<TelemetryProcessingClaim>> second =
                    submitClaim(executor, workersReady, start, "worker-b");
            assertThat(workersReady.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();
            List<Optional<TelemetryProcessingClaim>> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(Optional::isPresent).count()).isOne();
            TelemetryProcessingClaim winner =
                    results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(winner.event().id()).isEqualTo(fixture.eventId());
            assertThat(winner.attemptCount()).isOne();

            ProcessingRow row = readEvent(fixture.eventId());
            assertThat(row.status()).isEqualTo("PROCESSING");
            assertThat(row.attemptCount()).isOne();
            assertThat(row.claimToken()).isEqualTo(winner.claimToken());
            assertThat(row.claimOwner()).isIn("worker-a", "worker-b");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aLockedExhaustedLeaseDoesNotBlockClaimingUnrelatedDueWork() throws Exception {
        EventFixture exhausted = createPendingEvent("locked-exhausted");
        seedExpiredFifthLease(exhausted.eventId(), UUID.randomUUID(), CLAIMED_AT.minusSeconds(1));
        EventFixture due = createPendingEvent("unrelated-due");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch exhaustedLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        Future<?> lockingTransaction =
                executor.submit(
                        () ->
                                transactionTemplate.executeWithoutResult(
                                        status -> {
                                            jdbcClient
                                                    .sql(
                                                            """
                                                            SELECT id
                                                            FROM telemetry_processing_event
                                                            WHERE id = :eventId
                                                            FOR UPDATE
                                                            """)
                                                    .param("eventId", exhausted.eventId())
                                                    .query(UUID.class)
                                                    .single();
                                            exhaustedLocked.countDown();
                                            await(releaseLock);
                                        }));

        try {
            assertThat(exhaustedLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Optional<TelemetryProcessingClaim>> claim =
                    executor.submit(() -> lifecycleService.claimNext("worker-b", CLAIMED_AT));
            TelemetryProcessingClaim claimed = claim.get(5, TimeUnit.SECONDS).orElseThrow();

            assertThat(claimed.event().id()).isEqualTo(due.eventId());
            assertThat(claimed.attemptCount()).isOne();
        } finally {
            releaseLock.countDown();
            lockingTransaction.get(10, TimeUnit.SECONDS);
            executor.shutdownNow();
        }

        ProcessingRow exhaustedRow = readEvent(exhausted.eventId());
        assertThat(exhaustedRow.status()).isEqualTo("PROCESSING");
        assertThat(exhaustedRow.attemptCount()).isEqualTo(5);
        ProcessingRow dueRow = readEvent(due.eventId());
        assertThat(dueRow.status()).isEqualTo("PROCESSING");
        assertThat(dueRow.attemptCount()).isOne();
    }

    @Test
    void aLiveLeaseCannotBeStolen() {
        EventFixture fixture = createPendingEvent("live-lease");
        TelemetryProcessingClaim first =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();

        assertThat(lifecycleService.claimNext("worker-b", CLAIMED_AT.plusSeconds(29))).isEmpty();

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("PROCESSING");
        assertThat(row.attemptCount()).isOne();
        assertThat(row.claimToken()).isEqualTo(first.claimToken());
        assertThat(row.claimOwner()).isEqualTo("worker-a");
        assertThat(row.leaseExpiresAt()).isEqualTo(first.leaseExpiresAt());
        assertThat(row.updatedAt()).isEqualTo(CLAIMED_AT);
    }

    @Test
    void anExpiredLeaseIsRecoveredWithANewTokenAndAttempt() {
        EventFixture fixture = createPendingEvent("expired-lease");
        TelemetryProcessingClaim first =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();

        TelemetryProcessingClaim recovered =
                lifecycleService.claimNext("worker-b", first.leaseExpiresAt()).orElseThrow();

        assertThat(recovered.event().id()).isEqualTo(fixture.eventId());
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.leaseExpiresAt()).isEqualTo(CLAIMED_AT.plusSeconds(60));

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("PROCESSING");
        assertThat(row.attemptCount()).isEqualTo(2);
        assertThat(row.claimToken()).isEqualTo(recovered.claimToken());
        assertThat(row.claimOwner()).isEqualTo("worker-b");
        assertThat(row.updatedAt()).isEqualTo(first.leaseExpiresAt());
    }

    @Test
    void aStaleClaimCannotRunItsHandlerCompleteOrRecordFailure() {
        EventFixture fixture = createPendingEvent("stale-token");
        TelemetryProcessingClaim stale =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();
        Instant recoveredAt = stale.leaseExpiresAt();
        TelemetryProcessingClaim current =
                lifecycleService.claimNext("worker-b", recoveredAt).orElseThrow();
        AtomicBoolean handlerInvoked = new AtomicBoolean();

        assertThat(executionService.execute(stale, event -> handlerInvoked.set(true))).isFalse();
        assertThat(lifecycleService.recordFailure(stale, recoveredAt.plusSeconds(1)))
                .isEqualTo(TelemetryProcessingFailureDisposition.STALE_CLAIM);
        assertThat(handlerInvoked).isFalse();

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("PROCESSING");
        assertThat(row.attemptCount()).isEqualTo(2);
        assertThat(row.claimToken()).isEqualTo(current.claimToken());
        assertThat(row.claimOwner()).isEqualTo("worker-b");
        assertThat(row.leaseExpiresAt()).isEqualTo(current.leaseExpiresAt());
        assertThat(row.lastErrorCode()).isNull();
        assertThat(row.lastErrorMessage()).isNull();
    }

    @Test
    void aSuccessfulHandlerCommitsItsEffectAndCompletesTheEvent() {
        EventFixture fixture = createPendingEvent("successful-handler");
        TelemetryProcessingClaim claim =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();

        assertThat(
                        executionService.execute(
                                claim,
                                event ->
                                        updateBatchKey(
                                                event.telemetryBatchId(),
                                                "successful-handler-effect")))
                .isTrue();

        assertThat(readBatchKey(fixture.batchId())).isEqualTo("successful-handler-effect");
        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("COMPLETED");
        assertThat(row.attemptCount()).isOne();
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.claimToken()).isNull();
        assertThat(row.claimOwner()).isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.completedAt()).isNotNull();
        assertThat(row.deadAt()).isNull();
        assertThat(row.updatedAt()).isEqualTo(row.completedAt());
    }

    @Test
    void aFailingHandlerRollsBackBeforeAFixedSafeFailureSchedulesTheExactRetry() {
        EventFixture fixture = createPendingEvent("failing-handler");
        TelemetryProcessingClaim claim =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();

        assertThatThrownBy(
                        () ->
                                executionService.execute(
                                        claim,
                                        event -> {
                                            updateBatchKey(
                                                    event.telemetryBatchId(),
                                                    "must-be-rolled-back");
                                            throw new IllegalStateException(
                                                    "sensitive handler detail");
                                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("sensitive handler detail");

        assertThat(readBatchKey(fixture.batchId())).isEqualTo("failing-handler");
        ProcessingRow processing = readEvent(fixture.eventId());
        assertThat(processing.status()).isEqualTo("PROCESSING");
        assertThat(processing.claimToken()).isEqualTo(claim.claimToken());

        Instant failedAt = CLAIMED_AT.plusSeconds(2);
        assertThat(lifecycleService.recordFailure(claim, failedAt))
                .isEqualTo(TelemetryProcessingFailureDisposition.RETRY_SCHEDULED);

        ProcessingRow retry = readEvent(fixture.eventId());
        assertThat(retry.status()).isEqualTo("PENDING");
        assertThat(retry.attemptCount()).isOne();
        assertThat(retry.nextAttemptAt()).isEqualTo(failedAt.plusSeconds(5));
        assertThat(retry.claimToken()).isNull();
        assertThat(retry.claimOwner()).isNull();
        assertThat(retry.leaseExpiresAt()).isNull();
        assertThat(retry.lastErrorCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(retry.lastErrorMessage())
                .isEqualTo("Processing failed; another attempt may be scheduled.")
                .doesNotContain("sensitive handler detail");
        assertThat(retry.updatedAt()).isEqualTo(failedAt);

        assertThat(lifecycleService.claimNext("worker-b", retry.nextAttemptAt().minusMillis(1)))
                .isEmpty();
        assertThat(readEvent(fixture.eventId())).isEqualTo(retry);

        TelemetryProcessingClaim retried =
                lifecycleService.claimNext("worker-b", retry.nextAttemptAt()).orElseThrow();
        assertThat(retried.event().id()).isEqualTo(fixture.eventId());
        assertThat(retried.attemptCount()).isEqualTo(2);
        assertThat(retried.claimToken()).isNotEqualTo(claim.claimToken());
        ProcessingRow processingAgain = readEvent(fixture.eventId());
        assertThat(processingAgain.status()).isEqualTo("PROCESSING");
        assertThat(processingAgain.attemptCount()).isEqualTo(2);
        assertThat(processingAgain.lastErrorCode()).isNull();
        assertThat(processingAgain.lastErrorMessage()).isNull();
    }

    @Test
    void theFifthFailureBecomesDead() {
        EventFixture fixture = createPendingEvent("fifth-failure");
        seedRetriedPending(fixture.eventId(), 4, CLAIMED_AT.minusSeconds(1));
        TelemetryProcessingClaim fifth =
                lifecycleService.claimNext("worker-a", CLAIMED_AT).orElseThrow();
        Instant failedAt = CLAIMED_AT.plusSeconds(2);

        assertThat(fifth.attemptCount()).isEqualTo(5);
        assertThat(lifecycleService.recordFailure(fifth, failedAt))
                .isEqualTo(TelemetryProcessingFailureDisposition.DEAD);

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attemptCount()).isEqualTo(5);
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.claimToken()).isNull();
        assertThat(row.claimOwner()).isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.completedAt()).isNull();
        assertThat(row.deadAt()).isEqualTo(failedAt);
        assertThat(row.lastErrorCode()).isEqualTo("PROCESSING_FAILED");
        assertThat(row.lastErrorMessage())
                .isEqualTo("Processing failed; another attempt may be scheduled.");
        assertThat(row.updatedAt()).isEqualTo(failedAt);
    }

    @Test
    void anExpiredFifthLeaseBecomesDeadInsteadOfStartingASixthAttempt() {
        EventFixture fixture = createPendingEvent("exhausted-lease");
        UUID exhaustedToken = UUID.randomUUID();
        seedExpiredFifthLease(fixture.eventId(), exhaustedToken, CLAIMED_AT.minusSeconds(1));

        assertThat(lifecycleService.claimNext("worker-b", CLAIMED_AT)).isEmpty();

        ProcessingRow row = readEvent(fixture.eventId());
        assertThat(row.status()).isEqualTo("DEAD");
        assertThat(row.attemptCount()).isEqualTo(5);
        assertThat(row.nextAttemptAt()).isNull();
        assertThat(row.claimToken()).isNull();
        assertThat(row.claimOwner()).isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.completedAt()).isNull();
        assertThat(row.deadAt()).isEqualTo(CLAIMED_AT);
        assertThat(row.lastErrorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(row.lastErrorMessage())
                .isEqualTo("Processing lease expired after the final attempt.");
        assertThat(row.updatedAt()).isEqualTo(CLAIMED_AT);
    }

    private Future<Optional<TelemetryProcessingClaim>> submitClaim(
            ExecutorService executor,
            CountDownLatch workersReady,
            CountDownLatch start,
            String owner) {
        return executor.submit(
                () -> {
                    workersReady.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start claims");
                    }
                    return lifecycleService.claimNext(owner, CLAIMED_AT);
                });
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private EventFixture createPendingEvent(String idempotencyKey) {
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
                            1,
                            :acceptedAt
                        )
                        """)
                .param("id", batchId)
                .param("organisationId", NORTHSTAR_ID)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", "0".repeat(64))
                .param("acceptedAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();
        eventRepository.insertBatchAccepted(NORTHSTAR_ID, batchId, CREATED_AT);
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

    private void seedRetriedPending(UUID eventId, int attemptCount, Instant nextAttemptAt) {
        jdbcClient
                .sql(
                        """
                        UPDATE telemetry_processing_event
                        SET attempt_count = :attemptCount,
                            next_attempt_at = :nextAttemptAt,
                            updated_at = :nextAttemptAt,
                            last_error_code = 'PROCESSING_FAILED',
                            last_error_message = 'Processing failed; another attempt may be scheduled.'
                        WHERE id = :eventId
                        """)
                .param("attemptCount", attemptCount)
                .param("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
                .param("eventId", eventId)
                .update();
    }

    private void seedExpiredFifthLease(UUID eventId, UUID claimToken, Instant leaseExpiresAt) {
        jdbcClient
                .sql(
                        """
                        UPDATE telemetry_processing_event
                        SET status = 'PROCESSING',
                            attempt_count = 5,
                            next_attempt_at = NULL,
                            claim_token = :claimToken,
                            claim_owner = 'worker-a',
                            lease_expires_at = :leaseExpiresAt,
                            updated_at = :leaseExpiresAt
                        WHERE id = :eventId
                        """)
                .param("claimToken", claimToken)
                .param("leaseExpiresAt", leaseExpiresAt.atOffset(ZoneOffset.UTC))
                .param("eventId", eventId)
                .update();
    }

    private void updateBatchKey(UUID batchId, String idempotencyKey) {
        jdbcClient
                .sql(
                        """
                        UPDATE telemetry_batch
                        SET idempotency_key = :idempotencyKey
                        WHERE id = :batchId
                        """)
                .param("idempotencyKey", idempotencyKey)
                .param("batchId", batchId)
                .update();
    }

    private String readBatchKey(UUID batchId) {
        return jdbcClient
                .sql("SELECT idempotency_key FROM telemetry_batch WHERE id = :batchId")
                .param("batchId", batchId)
                .query(String.class)
                .single();
    }

    private ProcessingRow readEvent(UUID eventId) {
        return jdbcClient
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
                .query(TelemetryProcessingWorkerIntegrationTest::mapProcessingRow)
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

    private record EventFixture(UUID eventId, UUID batchId) {}

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
