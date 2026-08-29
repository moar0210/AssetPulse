package io.github.moar0210.assetpulse.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private static final String INSERT_EVENT =
            """
            INSERT INTO audit_event (
                id, organisation_id, actor_user_id, action, subject_user_id,
                subject_alert_id, subject_work_order_id, subject_processing_event_id,
                correlation_id
            ) VALUES (
                :id, :organisationId, :actorUserId, :action, :subjectUserId,
                :subjectAlertId, :subjectWorkOrderId, :subjectProcessingEventId,
                :correlationId
            )
            """;

    private static final String LIST_EVENTS =
            """
            SELECT
                event.id, event.actor_user_id, actor.display_name AS actor_display_name,
                event.action, event.occurred_at, event.correlation_id,
                COALESCE(event.subject_user_id, event.subject_alert_id,
                    event.subject_work_order_id, event.subject_processing_event_id) AS subject_id
            FROM audit_event event
            JOIN app_user actor
              ON actor.organisation_id = event.organisation_id
             AND actor.id = event.actor_user_id
            WHERE event.organisation_id = :organisationId
            ORDER BY event.occurred_at DESC, event.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    public AuditRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insert(
            UUID organisationId,
            UUID actorUserId,
            AuditAction action,
            UUID subjectId,
            UUID correlationId) {
        jdbcClient
                .sql(INSERT_EVENT)
                .param("id", UUID.randomUUID())
                .param("organisationId", organisationId)
                .param("actorUserId", actorUserId)
                .param("action", action.name())
                .param("subjectUserId", subjectFor(action, subjectId, AuditSubjectType.USER))
                .param("subjectAlertId", subjectFor(action, subjectId, AuditSubjectType.ALERT))
                .param(
                        "subjectWorkOrderId",
                        subjectFor(action, subjectId, AuditSubjectType.WORK_ORDER))
                .param(
                        "subjectProcessingEventId",
                        subjectFor(action, subjectId, AuditSubjectType.PROCESSING_EVENT))
                .param("correlationId", correlationId)
                .update();
    }

    public List<AuditEventResponse> findByOrganisationId(UUID organisationId, int limit) {
        return jdbcClient
                .sql(LIST_EVENTS)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(AuditRepository::mapEvent)
                .list();
    }

    private static UUID subjectFor(AuditAction action, UUID subjectId, AuditSubjectType type) {
        return action.subjectType() == type ? subjectId : null;
    }

    private static AuditEventResponse mapEvent(ResultSet resultSet, int rowNumber)
            throws SQLException {
        AuditAction action = AuditAction.valueOf(resultSet.getString("action"));
        return new AuditEventResponse(
                resultSet.getObject("id", UUID.class),
                new AuditActorResponse(
                        resultSet.getObject("actor_user_id", UUID.class),
                        resultSet.getString("actor_display_name")),
                action,
                new AuditSubjectResponse(
                        action.subjectType(), resultSet.getObject("subject_id", UUID.class)),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("correlation_id", UUID.class));
    }
}
