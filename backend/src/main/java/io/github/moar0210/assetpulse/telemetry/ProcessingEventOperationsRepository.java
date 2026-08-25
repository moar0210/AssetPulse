package io.github.moar0210.assetpulse.telemetry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessingEventOperationsRepository {

    private static final String LIST_DEAD_EVENTS =
            """
            SELECT
                id,
                telemetry_batch_id,
                event_type,
                attempt_count,
                created_at,
                dead_at,
                updated_at,
                last_error_code,
                last_error_message
            FROM telemetry_processing_event
            WHERE organisation_id = :organisationId
              AND status = 'DEAD'
            ORDER BY dead_at DESC, id
            LIMIT :limit
            """;

    private static final String RETRY_DEAD_EVENT =
            """
            UPDATE telemetry_processing_event
            SET status = 'PENDING',
                attempt_count = 0,
                next_attempt_at = :retriedAt,
                claim_token = NULL,
                claim_owner = NULL,
                lease_expires_at = NULL,
                completed_at = NULL,
                dead_at = NULL,
                updated_at = :retriedAt,
                last_error_code = NULL,
                last_error_message = NULL
            WHERE organisation_id = :organisationId
              AND id = :eventId
              AND status = 'DEAD'
            """;

    private static final String EXISTS_FOR_ORGANISATION =
            """
            SELECT EXISTS (
                SELECT 1
                FROM telemetry_processing_event
                WHERE organisation_id = :organisationId
                  AND id = :eventId
            )
            """;

    private final JdbcClient jdbcClient;

    public ProcessingEventOperationsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<DeadProcessingEventResponse> findDeadByOrganisationId(
            UUID organisationId, int limit) {
        return jdbcClient
                .sql(LIST_DEAD_EVENTS)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(ProcessingEventOperationsRepository::mapDeadEvent)
                .list();
    }

    public int retryDeadForOrganisation(UUID organisationId, UUID eventId, Instant retriedAt) {
        return jdbcClient
                .sql(RETRY_DEAD_EVENT)
                .param("organisationId", organisationId)
                .param("eventId", eventId)
                .param("retriedAt", retriedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public boolean existsByOrganisationIdAndId(UUID organisationId, UUID eventId) {
        return jdbcClient
                .sql(EXISTS_FOR_ORGANISATION)
                .param("organisationId", organisationId)
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    private static DeadProcessingEventResponse mapDeadEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DeadProcessingEventResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("telemetry_batch_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("attempt_count"),
                instant(resultSet, "created_at"),
                instant(resultSet, "dead_at"),
                instant(resultSet, "updated_at"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
