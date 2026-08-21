package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertCommandRepository {

    private static final String TRANSITION =
            """
            UPDATE alert
            SET status = :targetStatus,
                updated_at = GREATEST(updated_at, :transitionedAt)
            WHERE organisation_id = :organisationId
              AND id = :alertId
              AND status = :expectedStatus
            """;

    private static final String FIND_STATUS =
            """
            SELECT status
            FROM alert
            WHERE organisation_id = :organisationId
              AND id = :alertId
            """;

    private static final String INSERT_HISTORY =
            """
            INSERT INTO alert_status_history (
                organisation_id,
                alert_id,
                sequence_number,
                from_status,
                to_status,
                actor_user_id,
                transitioned_at
            )
            VALUES (
                :organisationId,
                :alertId,
                :sequenceNumber,
                :fromStatus,
                :toStatus,
                :actorUserId,
                :transitionedAt
            )
            """;

    private final JdbcClient jdbcClient;

    public AlertCommandRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public int transition(
            UUID organisationId,
            UUID alertId,
            AlertStatus expectedStatus,
            AlertStatus targetStatus,
            Instant transitionedAt) {
        return jdbcClient
                .sql(TRANSITION)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("expectedStatus", expectedStatus.name())
                .param("targetStatus", targetStatus.name())
                .param("transitionedAt", transitionedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<AlertStatus> findStatusByOrganisationIdAndId(
            UUID organisationId, UUID alertId) {
        return jdbcClient
                .sql(FIND_STATUS)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .query(String.class)
                .optional()
                .map(AlertStatus::valueOf);
    }

    public void insertHistory(
            UUID organisationId,
            UUID alertId,
            int sequenceNumber,
            AlertStatus fromStatus,
            AlertStatus toStatus,
            UUID actorUserId,
            Instant transitionedAt) {
        int inserted =
                jdbcClient
                        .sql(INSERT_HISTORY)
                        .param("organisationId", organisationId)
                        .param("alertId", alertId)
                        .param("sequenceNumber", sequenceNumber)
                        .param("fromStatus", fromStatus.name())
                        .param("toStatus", toStatus.name())
                        .param("actorUserId", actorUserId)
                        .param("transitionedAt", transitionedAt.atOffset(ZoneOffset.UTC))
                        .update();
        if (inserted != 1) {
            throw new IllegalStateException("Alert status history was not recorded");
        }
    }
}
