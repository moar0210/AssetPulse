package io.github.moar0210.assetpulse.demo;

import io.github.moar0210.assetpulse.alerts.AlertStatus;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.workorders.WorkOrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DemoResetRepository {

    private static final String FIND_ACTIVE_ALERTS_FOR_UPDATE =
            """
            SELECT id, status
            FROM alert
            WHERE organisation_id = :organisationId
              AND status IN ('OPEN', 'ACKNOWLEDGED')
            ORDER BY id
            LIMIT :limit
            FOR UPDATE
            """;

    private static final String FIND_ACTIVE_WORK_ORDERS_FOR_UPDATE =
            """
            SELECT id, status, version, assigned_technician_user_id
            FROM work_order
            WHERE organisation_id = :organisationId
              AND status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')
            ORDER BY id
            LIMIT :limit
            FOR UPDATE
            """;

    private static final String TECHNICIAN_COLUMNS =
            """
            SELECT
                app_user.id,
                app_user.email,
                app_user.display_name,
                organisation.id AS organisation_id,
                organisation.slug AS organisation_slug,
                organisation.name AS organisation_name,
                app_role.code AS role_code,
                app_role.display_name AS role_display_name
            FROM app_user
            JOIN organisation ON organisation.id = app_user.organisation_id
            JOIN app_role ON app_role.code = app_user.role_code
            """;

    private static final String FIND_FIRST_TECHNICIAN =
            TECHNICIAN_COLUMNS
                    + """
                    WHERE app_user.organisation_id = :organisationId
                      AND app_user.role_code = 'TECHNICIAN'
                    ORDER BY app_user.display_name, app_user.id
                    LIMIT 1
                    """;

    private static final String FIND_TECHNICIAN =
            TECHNICIAN_COLUMNS
                    + """
                    WHERE app_user.organisation_id = :organisationId
                      AND app_user.id = :technicianUserId
                      AND app_user.role_code = 'TECHNICIAN'
                    """;

    private final JdbcClient jdbcClient;

    public DemoResetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ActiveAlert> findActiveAlertsForUpdate(UUID organisationId, int limit) {
        return jdbcClient
                .sql(FIND_ACTIVE_ALERTS_FOR_UPDATE)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(
                        (resultSet, rowNumber) ->
                                new ActiveAlert(
                                        resultSet.getObject("id", UUID.class),
                                        AlertStatus.valueOf(resultSet.getString("status"))))
                .list();
    }

    public List<ActiveWorkOrder> findActiveWorkOrdersForUpdate(UUID organisationId, int limit) {
        return jdbcClient
                .sql(FIND_ACTIVE_WORK_ORDERS_FOR_UPDATE)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(
                        (resultSet, rowNumber) ->
                                new ActiveWorkOrder(
                                        resultSet.getObject("id", UUID.class),
                                        WorkOrderStatus.valueOf(resultSet.getString("status")),
                                        resultSet.getLong("version"),
                                        resultSet.getObject(
                                                "assigned_technician_user_id", UUID.class)))
                .list();
    }

    public Optional<AuthenticatedActor> findFirstTechnician(UUID organisationId) {
        return jdbcClient
                .sql(FIND_FIRST_TECHNICIAN)
                .param("organisationId", organisationId)
                .query(DemoResetRepository::mapTechnician)
                .optional();
    }

    public Optional<AuthenticatedActor> findTechnician(UUID organisationId, UUID technicianUserId) {
        return jdbcClient
                .sql(FIND_TECHNICIAN)
                .param("organisationId", organisationId)
                .param("technicianUserId", technicianUserId)
                .query(DemoResetRepository::mapTechnician)
                .optional();
    }

    private static AuthenticatedActor mapTechnician(ResultSet resultSet, int rowNumber)
            throws SQLException {
        AuthenticatedActor actor =
                new AuthenticatedActor(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("email"),
                        resultSet.getString("display_name"),
                        "not-used-for-demo-reset",
                        resultSet.getObject("organisation_id", UUID.class),
                        resultSet.getString("organisation_slug"),
                        resultSet.getString("organisation_name"),
                        resultSet.getString("role_code"),
                        resultSet.getString("role_display_name"));
        actor.eraseCredentials();
        return actor;
    }

    public record ActiveAlert(UUID id, AlertStatus status) {}

    public record ActiveWorkOrder(
            UUID id, WorkOrderStatus status, long version, UUID technicianUserId) {}
}
