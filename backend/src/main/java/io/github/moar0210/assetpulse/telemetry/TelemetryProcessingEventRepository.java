package io.github.moar0210.assetpulse.telemetry;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryProcessingEventRepository {

    private static final String INSERT_ACCEPTED_EVENT =
            """
            INSERT INTO telemetry_processing_event (
                id,
                organisation_id,
                telemetry_batch_id,
                event_type,
                created_at,
                next_attempt_at,
                updated_at
            )
            VALUES (
                :id,
                :organisationId,
                :telemetryBatchId,
                'TELEMETRY_BATCH_ACCEPTED',
                :createdAt,
                :createdAt,
                :createdAt
            )
            """;

    private static final String MOVE_EXHAUSTED_EXPIRED_LEASES_TO_DEAD =
            """
            WITH exhausted AS (
                SELECT id
                FROM telemetry_processing_event
                WHERE status = 'PROCESSING'
                  AND lease_expires_at <= :now
                  AND attempt_count >= :maxAttempts
                ORDER BY lease_expires_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 10
            )
            UPDATE telemetry_processing_event event
            SET status = 'DEAD',
                claim_token = NULL,
                claim_owner = NULL,
                lease_expires_at = NULL,
                dead_at = :now,
                updated_at = :now,
                last_error_code = :errorCode,
                last_error_message = :errorMessage
            FROM exhausted
            WHERE event.id = exhausted.id
            """;

    private static final String CLAIM_NEXT =
            """
            WITH candidate AS (
                SELECT id
                FROM telemetry_processing_event
                WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                   OR (
                       status = 'PROCESSING'
                       AND lease_expires_at <= :now
                       AND attempt_count < :maxAttempts
                   )
                ORDER BY
                    CASE
                        WHEN status = 'PENDING' THEN next_attempt_at
                        ELSE lease_expires_at
                    END,
                    created_at,
                    id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE telemetry_processing_event event
            SET status = 'PROCESSING',
                attempt_count = event.attempt_count + 1,
                next_attempt_at = NULL,
                claim_token = :claimToken,
                claim_owner = :claimOwner,
                lease_expires_at = :leaseExpiresAt,
                completed_at = NULL,
                dead_at = NULL,
                updated_at = :now,
                last_error_code = NULL,
                last_error_message = NULL
            FROM candidate
            WHERE event.id = candidate.id
            RETURNING
                event.id,
                event.organisation_id,
                event.telemetry_batch_id,
                event.event_type,
                event.created_at,
                event.claim_token,
                event.attempt_count,
                event.lease_expires_at
            """;

    private static final String LOCK_CLAIM =
            """
            SELECT
                id,
                organisation_id,
                telemetry_batch_id,
                event_type,
                created_at
            FROM telemetry_processing_event
            WHERE id = :eventId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            FOR UPDATE
            """;

    private static final String COMPLETE =
            """
            UPDATE telemetry_processing_event
            SET status = 'COMPLETED',
                claim_token = NULL,
                claim_owner = NULL,
                lease_expires_at = NULL,
                completed_at = :completedAt,
                updated_at = :completedAt,
                last_error_code = NULL,
                last_error_message = NULL
            WHERE id = :eventId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            """;

    private static final String RECORD_FAILURE =
            """
            UPDATE telemetry_processing_event
            SET status = CASE
                    WHEN attempt_count >= :maxAttempts THEN 'DEAD'
                    ELSE 'PENDING'
                END,
                next_attempt_at = CASE
                    WHEN attempt_count >= :maxAttempts THEN NULL
                    ELSE CAST(:nextAttemptAt AS TIMESTAMP WITH TIME ZONE)
                END,
                claim_token = NULL,
                claim_owner = NULL,
                lease_expires_at = NULL,
                dead_at = CASE
                    WHEN attempt_count >= :maxAttempts THEN :failedAt
                    ELSE NULL
                END,
                updated_at = :failedAt,
                last_error_code = :errorCode,
                last_error_message = :errorMessage
            WHERE id = :eventId
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
            RETURNING CASE
                WHEN status = 'DEAD' THEN 'DEAD'
                ELSE 'RETRY_SCHEDULED'
            END
            """;

    private final JdbcClient jdbcClient;

    public TelemetryProcessingEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertBatchAccepted(UUID organisationId, UUID telemetryBatchId, Instant createdAt) {
        jdbcClient
                .sql(INSERT_ACCEPTED_EVENT)
                .param("id", UUID.randomUUID())
                .param("organisationId", organisationId)
                .param("telemetryBatchId", telemetryBatchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public int moveExhaustedExpiredLeasesToDead(
            Instant now, int maxAttempts, String errorCode, String errorMessage) {
        return jdbcClient
                .sql(MOVE_EXHAUSTED_EXPIRED_LEASES_TO_DEAD)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("maxAttempts", maxAttempts)
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update();
    }

    public Optional<TelemetryProcessingClaim> claimNext(
            String claimOwner,
            UUID claimToken,
            Instant now,
            Instant leaseExpiresAt,
            int maxAttempts) {
        return jdbcClient
                .sql(CLAIM_NEXT)
                .param("claimOwner", claimOwner)
                .param("claimToken", claimToken)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("leaseExpiresAt", leaseExpiresAt.atOffset(ZoneOffset.UTC))
                .param("maxAttempts", maxAttempts)
                .query(
                        (resultSet, rowNumber) ->
                                TelemetryProcessingEventRepository.mapClaim(resultSet, rowNumber))
                .optional();
    }

    public Optional<TelemetryProcessingEvent> lockClaim(UUID eventId, UUID claimToken) {
        return jdbcClient
                .sql(LOCK_CLAIM)
                .param("eventId", eventId)
                .param("claimToken", claimToken)
                .query(
                        (resultSet, rowNumber) ->
                                TelemetryProcessingEventRepository.mapEvent(resultSet, rowNumber))
                .optional();
    }

    public boolean complete(UUID eventId, UUID claimToken, Instant completedAt) {
        return jdbcClient
                        .sql(COMPLETE)
                        .param("eventId", eventId)
                        .param("claimToken", claimToken)
                        .param("completedAt", completedAt.atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }

    public Optional<String> recordFailure(
            UUID eventId,
            UUID claimToken,
            Instant failedAt,
            Instant nextAttemptAt,
            int maxAttempts,
            String errorCode,
            String errorMessage) {
        JdbcClient.StatementSpec statement =
                jdbcClient
                        .sql(RECORD_FAILURE)
                        .param("eventId", eventId)
                        .param("claimToken", claimToken)
                        .param("failedAt", failedAt.atOffset(ZoneOffset.UTC))
                        .param("maxAttempts", maxAttempts)
                        .param("errorCode", errorCode)
                        .param("errorMessage", errorMessage);
        statement =
                nextAttemptAt == null
                        ? statement.param("nextAttemptAt", null, Types.TIMESTAMP_WITH_TIMEZONE)
                        : statement.param("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC));
        return statement.query(String.class).optional();
    }

    private static TelemetryProcessingClaim mapClaim(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new TelemetryProcessingClaim(
                mapEvent(resultSet),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant());
    }

    private static TelemetryProcessingEvent mapEvent(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return mapEvent(resultSet);
    }

    private static TelemetryProcessingEvent mapEvent(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        return new TelemetryProcessingEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organisation_id", UUID.class),
                resultSet.getObject("telemetry_batch_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
