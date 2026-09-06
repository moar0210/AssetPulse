package io.github.moar0210.assetpulse.dashboard;

import io.github.moar0210.assetpulse.audit.AuditAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DashboardRepository {

    private static final String COUNT_SUMMARY =
            """
            SELECT
                (
                    SELECT COUNT(*)
                    FROM asset
                    WHERE organisation_id = :organisationId
                ) AS asset_count,
                (
                    SELECT COUNT(*)
                    FROM alert
                    WHERE organisation_id = :organisationId
                      AND status = 'OPEN'
                ) AS open_alert_count,
                (
                    SELECT COUNT(*)
                    FROM work_order
                    WHERE organisation_id = :organisationId
                      AND status <> 'DONE'
                      AND (
                          NOT :technicianScoped
                          OR assigned_technician_user_id = :actorUserId
                      )
                ) AS active_work_order_count
            """;

    private static final String LIST_RECENT_ACTIVITY =
            """
            SELECT
                event.action,
                COALESCE(event.subject_alert_id, event.subject_work_order_id) AS subject_id,
                event.occurred_at
            FROM audit_event event
            WHERE event.organisation_id = :organisationId
              AND event.action IN (
                  'ALERT_ACKNOWLEDGED',
                  'ALERT_RESOLVED',
                  'WORK_ORDER_CREATED',
                  'WORK_ORDER_ASSIGNED',
                  'WORK_ORDER_STARTED',
                  'WORK_ORDER_COMPLETED'
              )
              AND (
                  NOT :technicianScoped
                  OR event.subject_alert_id IS NOT NULL
                  OR EXISTS (
                      SELECT 1
                      FROM work_order
                      WHERE work_order.organisation_id = event.organisation_id
                        AND work_order.id = event.subject_work_order_id
                        AND work_order.assigned_technician_user_id = :actorUserId
                  )
              )
            ORDER BY event.occurred_at DESC, event.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbcClient;

    public DashboardRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DashboardCounts countSummary(
            UUID organisationId, UUID actorUserId, boolean technicianScoped) {
        return jdbcClient
                .sql(COUNT_SUMMARY)
                .param("organisationId", organisationId)
                .param("actorUserId", actorUserId)
                .param("technicianScoped", technicianScoped)
                .query(
                        (resultSet, rowNumber) ->
                                new DashboardCounts(
                                        resultSet.getLong("asset_count"),
                                        resultSet.getLong("open_alert_count"),
                                        resultSet.getLong("active_work_order_count")))
                .single();
    }

    public List<DashboardActivityResponse> findRecentActivity(
            UUID organisationId, UUID actorUserId, boolean technicianScoped, int limit) {
        return jdbcClient
                .sql(LIST_RECENT_ACTIVITY)
                .param("organisationId", organisationId)
                .param("actorUserId", actorUserId)
                .param("technicianScoped", technicianScoped)
                .param("limit", limit)
                .query(DashboardRepository::mapActivity)
                .list();
    }

    private static DashboardActivityResponse mapActivity(ResultSet resultSet, int rowNumber)
            throws SQLException {
        AuditAction action = AuditAction.valueOf(resultSet.getString("action"));
        return new DashboardActivityResponse(
                action,
                action.subjectType(),
                resultSet.getObject("subject_id", UUID.class),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    public record DashboardCounts(
            long assetCount, long openAlertCount, long activeWorkOrderCount) {}
}
