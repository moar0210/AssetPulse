package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.assets.MeasurementType;
import io.github.moar0210.assetpulse.assets.MeasurementUnit;
import io.github.moar0210.assetpulse.assets.ThresholdComparison;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AlertQueryRepository {

    private static final String ALERT_COLUMNS =
            """
            SELECT
                alert.id,
                alert.status,
                alert.occurrence_count,
                alert.first_occurred_at,
                alert.last_occurred_at,
                alert.cooldown_until,
                alert.created_at,
                alert.updated_at,
                asset.id AS asset_id,
                asset.asset_code,
                asset.name AS asset_name,
                sensor.id AS sensor_id,
                sensor.sensor_key,
                sensor.name AS sensor_name,
                sensor.measurement_type,
                sensor.unit,
                rule.id AS rule_id,
                rule.rule_code,
                rule.name AS rule_name,
                rule.comparison,
                rule.threshold_value,
                rule.cooldown_seconds
            FROM alert
            JOIN threshold_rule rule
              ON rule.organisation_id = alert.organisation_id
             AND rule.id = alert.threshold_rule_id
            JOIN sensor
              ON sensor.organisation_id = rule.organisation_id
             AND sensor.id = rule.sensor_id
            JOIN asset
              ON asset.organisation_id = sensor.organisation_id
             AND asset.id = sensor.asset_id
            """;

    private static final String LIST_ALERTS =
            ALERT_COLUMNS
                    + """
                    WHERE alert.organisation_id = :organisationId
                    ORDER BY alert.last_occurred_at DESC, alert.id
                    LIMIT :limit
                    """;

    private static final String FIND_ALERT =
            ALERT_COLUMNS
                    + """
                    WHERE alert.organisation_id = :organisationId
                      AND alert.id = :alertId
                    """;

    private static final String FIND_HISTORY =
            """
            SELECT
                history.sequence_number,
                history.from_status,
                history.to_status,
                history.transitioned_at,
                actor.id AS actor_id,
                actor.display_name AS actor_display_name
            FROM alert_status_history history
            JOIN app_user actor
              ON actor.organisation_id = history.organisation_id
             AND actor.id = history.actor_user_id
            WHERE history.organisation_id = :organisationId
              AND history.alert_id = :alertId
            ORDER BY history.sequence_number
            """;

    private final JdbcClient jdbcClient;

    public AlertQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AlertSummaryResponse> findByOrganisationId(UUID organisationId, int limit) {
        return jdbcClient
                .sql(LIST_ALERTS)
                .param("organisationId", organisationId)
                .param("limit", limit)
                .query(AlertQueryRepository::mapSummary)
                .list();
    }

    public Optional<AlertDetailResponse> findByOrganisationIdAndId(
            UUID organisationId, UUID alertId) {
        Optional<AlertRow> alert =
                jdbcClient
                        .sql(FIND_ALERT)
                        .param("organisationId", organisationId)
                        .param("alertId", alertId)
                        .query(AlertQueryRepository::mapAlert)
                        .optional();
        return alert.map(row -> toDetail(row, findHistory(organisationId, alertId)));
    }

    private List<AlertHistoryResponse> findHistory(UUID organisationId, UUID alertId) {
        return jdbcClient
                .sql(FIND_HISTORY)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .query(
                        (resultSet, rowNumber) ->
                                new AlertHistoryResponse(
                                        resultSet.getInt("sequence_number"),
                                        AlertStatus.valueOf(resultSet.getString("from_status")),
                                        AlertStatus.valueOf(resultSet.getString("to_status")),
                                        new AlertHistoryResponse.ActorResponse(
                                                resultSet.getObject("actor_id", UUID.class),
                                                resultSet.getString("actor_display_name")),
                                        instant(resultSet, "transitioned_at")))
                .list();
    }

    private static AlertSummaryResponse mapSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        AlertRow row = mapAlert(resultSet, rowNumber);
        return new AlertSummaryResponse(
                row.id(), row.status(), row.occurrenceCount(), row.lastOccurredAt(), row.context());
    }

    private static AlertRow mapAlert(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AlertRow(
                resultSet.getObject("id", UUID.class),
                AlertStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("occurrence_count"),
                instant(resultSet, "first_occurred_at"),
                instant(resultSet, "last_occurred_at"),
                instant(resultSet, "cooldown_until"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                new AlertContextResponse(
                        new AlertContextResponse.AssetResponse(
                                resultSet.getObject("asset_id", UUID.class),
                                resultSet.getString("asset_code"),
                                resultSet.getString("asset_name")),
                        new AlertContextResponse.SensorResponse(
                                resultSet.getObject("sensor_id", UUID.class),
                                resultSet.getString("sensor_key"),
                                resultSet.getString("sensor_name"),
                                MeasurementType.valueOf(resultSet.getString("measurement_type")),
                                MeasurementUnit.valueOf(resultSet.getString("unit"))),
                        new AlertContextResponse.ThresholdRuleResponse(
                                resultSet.getObject("rule_id", UUID.class),
                                resultSet.getString("rule_code"),
                                resultSet.getString("rule_name"),
                                ThresholdComparison.valueOf(resultSet.getString("comparison")),
                                resultSet.getBigDecimal("threshold_value"),
                                resultSet.getInt("cooldown_seconds"))));
    }

    private static AlertDetailResponse toDetail(
            AlertRow alert, List<AlertHistoryResponse> history) {
        return new AlertDetailResponse(
                alert.id(),
                alert.status(),
                alert.occurrenceCount(),
                alert.firstOccurredAt(),
                alert.lastOccurredAt(),
                alert.cooldownUntil(),
                alert.createdAt(),
                alert.updatedAt(),
                alert.context(),
                history);
    }

    private static java.time.Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record AlertRow(
            UUID id,
            AlertStatus status,
            long occurrenceCount,
            java.time.Instant firstOccurredAt,
            java.time.Instant lastOccurredAt,
            java.time.Instant cooldownUntil,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            AlertContextResponse context) {}
}
