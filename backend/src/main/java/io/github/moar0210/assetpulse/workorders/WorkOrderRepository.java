package io.github.moar0210.assetpulse.workorders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorkOrderRepository {

    private static final int ELIGIBLE_TECHNICIAN_LIMIT = 100;

    private static final String WORK_ORDER_COLUMNS =
            """
            SELECT
                work_order.id,
                work_order.alert_id,
                work_order.status,
                work_order.version,
                work_order.created_at,
                work_order.updated_at,
                technician.id AS technician_id,
                technician.display_name AS technician_display_name,
                asset.id AS asset_id,
                asset.asset_code,
                asset.name AS asset_name,
                rule.name AS rule_name
            FROM work_order
            JOIN alert
              ON alert.organisation_id = work_order.organisation_id
             AND alert.id = work_order.alert_id
            JOIN threshold_rule rule
              ON rule.organisation_id = alert.organisation_id
             AND rule.id = alert.threshold_rule_id
            JOIN sensor
              ON sensor.organisation_id = rule.organisation_id
             AND sensor.id = rule.sensor_id
            JOIN asset
              ON asset.organisation_id = sensor.organisation_id
             AND asset.id = sensor.asset_id
            LEFT JOIN app_user technician
              ON technician.organisation_id = work_order.organisation_id
             AND technician.id = work_order.assigned_technician_user_id
             AND technician.role_code = 'TECHNICIAN'
            """;

    private static final String LIST_FOR_ORGANISATION =
            WORK_ORDER_COLUMNS
                    + """
                    WHERE work_order.organisation_id = :organisationId
                    ORDER BY work_order.updated_at DESC, work_order.id
                    LIMIT :limit
                    """;

    private static final String LIST_FOR_TECHNICIAN =
            WORK_ORDER_COLUMNS
                    + """
                    WHERE work_order.organisation_id = :organisationId
                      AND work_order.assigned_technician_user_id = :technicianUserId
                    ORDER BY work_order.updated_at DESC, work_order.id
                    LIMIT :limit
                    """;

    private static final String FIND_FOR_ORGANISATION =
            WORK_ORDER_COLUMNS
                    + """
                    WHERE work_order.organisation_id = :organisationId
                      AND work_order.id = :workOrderId
                    """;

    private static final String FIND_FOR_TECHNICIAN =
            WORK_ORDER_COLUMNS
                    + """
                    WHERE work_order.organisation_id = :organisationId
                      AND work_order.id = :workOrderId
                      AND work_order.assigned_technician_user_id = :technicianUserId
                    """;

    private static final String INSERT_FROM_ALERT =
            """
            INSERT INTO work_order (
                id,
                organisation_id,
                alert_id,
                created_at,
                updated_at
            )
            SELECT
                :workOrderId,
                alert.organisation_id,
                alert.id,
                :createdAt,
                :createdAt
            FROM alert
            WHERE alert.organisation_id = :organisationId
              AND alert.id = :alertId
            ON CONFLICT (organisation_id, alert_id) DO NOTHING
            """;

    private static final String SOURCE_ALERT_EXISTS =
            """
            SELECT EXISTS (
                SELECT 1
                FROM alert
                WHERE organisation_id = :organisationId
                  AND id = :alertId
            )
            """;

    private static final String WORK_ORDER_EXISTS =
            """
            SELECT EXISTS (
                SELECT 1
                FROM work_order
                WHERE organisation_id = :organisationId
                  AND id = :workOrderId
            )
            """;

    private static final String ELIGIBLE_TECHNICIAN_EXISTS =
            """
            SELECT EXISTS (
                SELECT 1
                FROM app_user
                WHERE organisation_id = :organisationId
                  AND id = :technicianUserId
                  AND role_code = 'TECHNICIAN'
            )
            """;

    private static final String LIST_ELIGIBLE_TECHNICIANS =
            """
            SELECT id, display_name
            FROM app_user
            WHERE organisation_id = :organisationId
              AND role_code = 'TECHNICIAN'
            ORDER BY display_name, id
            LIMIT :limit
            """;

    private static final String ASSIGN =
            """
            UPDATE work_order
            SET status = 'ASSIGNED',
                version = version + 1,
                assigned_technician_user_id = :technicianUserId,
                assigned_technician_role_code = 'TECHNICIAN',
                assigned_at = GREATEST(updated_at, :assignedAt),
                updated_at = GREATEST(updated_at, :assignedAt)
            WHERE organisation_id = :organisationId
              AND id = :workOrderId
              AND status = 'OPEN'
              AND version = :expectedVersion
            """;

    private final JdbcClient jdbcClient;

    public WorkOrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<WorkOrderResponse> findByOrganisationId(UUID organisationId, int limit) {
        return jdbcClient
                .sql(LIST_FOR_ORGANISATION)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(WorkOrderRepository::mapWorkOrder)
                .list();
    }

    public List<WorkOrderResponse> findAssignedByOrganisationIdAndTechnicianId(
            UUID organisationId, UUID technicianUserId, int limit) {
        return jdbcClient
                .sql(LIST_FOR_TECHNICIAN)
                .param("organisationId", organisationId)
                .param("technicianUserId", technicianUserId)
                .param("limit", limit)
                .query(WorkOrderRepository::mapWorkOrder)
                .list();
    }

    public Optional<WorkOrderResponse> findByOrganisationIdAndId(
            UUID organisationId, UUID workOrderId) {
        return jdbcClient
                .sql(FIND_FOR_ORGANISATION)
                .param("organisationId", organisationId)
                .param("workOrderId", workOrderId)
                .query(WorkOrderRepository::mapWorkOrder)
                .optional();
    }

    public Optional<WorkOrderResponse> findAssignedByOrganisationIdAndIdAndTechnicianId(
            UUID organisationId, UUID workOrderId, UUID technicianUserId) {
        return jdbcClient
                .sql(FIND_FOR_TECHNICIAN)
                .param("organisationId", organisationId)
                .param("workOrderId", workOrderId)
                .param("technicianUserId", technicianUserId)
                .query(WorkOrderRepository::mapWorkOrder)
                .optional();
    }

    public int insertFromAlert(
            UUID workOrderId, UUID organisationId, UUID alertId, Instant createdAt) {
        return jdbcClient
                .sql(INSERT_FROM_ALERT)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public boolean sourceAlertExists(UUID organisationId, UUID alertId) {
        return jdbcClient
                .sql(SOURCE_ALERT_EXISTS)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .query(Boolean.class)
                .single();
    }

    public boolean workOrderExists(UUID organisationId, UUID workOrderId) {
        return jdbcClient
                .sql(WORK_ORDER_EXISTS)
                .param("organisationId", organisationId)
                .param("workOrderId", workOrderId)
                .query(Boolean.class)
                .single();
    }

    public boolean eligibleTechnicianExists(UUID organisationId, UUID technicianUserId) {
        return jdbcClient
                .sql(ELIGIBLE_TECHNICIAN_EXISTS)
                .param("organisationId", organisationId)
                .param("technicianUserId", technicianUserId)
                .query(Boolean.class)
                .single();
    }

    public List<EligibleTechnicianResponse> findEligibleTechnicians(UUID organisationId) {
        return jdbcClient
                .sql(LIST_ELIGIBLE_TECHNICIANS)
                .param("organisationId", organisationId)
                .param("limit", ELIGIBLE_TECHNICIAN_LIMIT)
                .query(
                        (resultSet, rowNumber) ->
                                new EligibleTechnicianResponse(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getString("display_name")))
                .list();
    }

    public int assign(
            UUID organisationId,
            UUID workOrderId,
            UUID technicianUserId,
            long expectedVersion,
            Instant assignedAt) {
        return jdbcClient
                .sql(ASSIGN)
                .param("organisationId", organisationId)
                .param("workOrderId", workOrderId)
                .param("technicianUserId", technicianUserId)
                .param("expectedVersion", expectedVersion)
                .param("assignedAt", assignedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static WorkOrderResponse mapWorkOrder(ResultSet resultSet, int rowNumber)
            throws SQLException {
        UUID technicianId = resultSet.getObject("technician_id", UUID.class);
        AssignedTechnicianResponse technician =
                technicianId == null
                        ? null
                        : new AssignedTechnicianResponse(
                                technicianId, resultSet.getString("technician_display_name"));
        return new WorkOrderResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("alert_id", UUID.class),
                WorkOrderStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("version"),
                technician,
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                new WorkOrderContextResponse(
                        resultSet.getObject("asset_id", UUID.class),
                        resultSet.getString("asset_code"),
                        resultSet.getString("asset_name"),
                        resultSet.getString("rule_name")));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
