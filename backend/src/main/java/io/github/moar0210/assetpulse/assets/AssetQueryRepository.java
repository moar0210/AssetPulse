package io.github.moar0210.assetpulse.assets;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetQueryRepository {

    private static final String FIND_BY_ORGANISATION =
            """
            SELECT id, asset_code, name
            FROM asset
            WHERE organisation_id = :organisationId
            ORDER BY name, id
            LIMIT 100
            """;

    private static final String FIND_ONE_BY_ORGANISATION_AND_ID =
            """
            SELECT id, asset_code, name
            FROM asset
            WHERE organisation_id = :organisationId
              AND id = :assetId
            """;

    private static final String FIND_SENSOR_CONFIGURATION =
            """
            SELECT
                s.id AS sensor_id,
                s.sensor_key,
                s.name AS sensor_name,
                s.measurement_type,
                s.unit,
                r.id AS rule_id,
                r.rule_code,
                r.name AS rule_name,
                r.comparison,
                r.threshold_value,
                r.cooldown_seconds,
                r.enabled
            FROM (
                SELECT *
                FROM sensor
                WHERE organisation_id = :organisationId
                  AND asset_id = :assetId
                ORDER BY name, id
                LIMIT 100
            ) s
            LEFT JOIN LATERAL (
                SELECT *
                FROM threshold_rule
                WHERE organisation_id = s.organisation_id
                  AND sensor_id = s.id
                ORDER BY name, id
                LIMIT 100
            ) r ON TRUE
            ORDER BY s.name, s.id, r.name, r.id
            """;

    private final JdbcClient jdbcClient;

    public AssetQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AssetSummaryResponse> findByOrganisationId(UUID organisationId) {
        return jdbcClient
                .sql(FIND_BY_ORGANISATION)
                .param("organisationId", organisationId)
                .query(
                        (resultSet, rowNumber) ->
                                new AssetSummaryResponse(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getString("asset_code"),
                                        resultSet.getString("name")))
                .list();
    }

    public Optional<AssetRow> findOneByOrganisationIdAndId(UUID organisationId, UUID assetId) {
        return jdbcClient
                .sql(FIND_ONE_BY_ORGANISATION_AND_ID)
                .param("organisationId", organisationId)
                .param("assetId", assetId)
                .query(
                        (resultSet, rowNumber) ->
                                new AssetRow(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getString("asset_code"),
                                        resultSet.getString("name")))
                .optional();
    }

    public List<AssetDetailResponse.SensorResponse> findSensorsByOrganisationIdAndAssetId(
            UUID organisationId, UUID assetId) {
        List<SensorConfigurationRow> rows =
                jdbcClient
                        .sql(FIND_SENSOR_CONFIGURATION)
                        .param("organisationId", organisationId)
                        .param("assetId", assetId)
                        .query(
                                (resultSet, rowNumber) ->
                                        new SensorConfigurationRow(
                                                resultSet.getObject("sensor_id", UUID.class),
                                                resultSet.getString("sensor_key"),
                                                resultSet.getString("sensor_name"),
                                                MeasurementType.valueOf(
                                                        resultSet.getString("measurement_type")),
                                                MeasurementUnit.valueOf(
                                                        resultSet.getString("unit")),
                                                resultSet.getObject("rule_id", UUID.class),
                                                resultSet.getString("rule_code"),
                                                resultSet.getString("rule_name"),
                                                resultSet.getString("comparison"),
                                                resultSet.getBigDecimal("threshold_value"),
                                                resultSet.getObject(
                                                        "cooldown_seconds", Integer.class),
                                                resultSet.getObject("enabled", Boolean.class)))
                        .list();

        List<AssetDetailResponse.SensorResponse> sensors = new ArrayList<>();
        UUID currentSensorId = null;
        List<AssetDetailResponse.ThresholdRuleResponse> currentRules = null;
        SensorConfigurationRow currentSensor = null;
        for (SensorConfigurationRow row : rows) {
            if (!row.sensorId().equals(currentSensorId)) {
                if (currentSensor != null) {
                    sensors.add(toSensor(currentSensor, currentRules));
                }
                currentSensorId = row.sensorId();
                currentSensor = row;
                currentRules = new ArrayList<>();
            }
            if (row.ruleId() != null) {
                currentRules.add(
                        new AssetDetailResponse.ThresholdRuleResponse(
                                row.ruleId(),
                                row.ruleCode(),
                                row.ruleName(),
                                ThresholdComparison.valueOf(row.comparison()),
                                row.thresholdValue(),
                                row.cooldownSeconds(),
                                row.enabled()));
            }
        }
        if (currentSensor != null) {
            sensors.add(toSensor(currentSensor, currentRules));
        }
        return List.copyOf(sensors);
    }

    private AssetDetailResponse.SensorResponse toSensor(
            SensorConfigurationRow sensor,
            List<AssetDetailResponse.ThresholdRuleResponse> thresholdRules) {
        return new AssetDetailResponse.SensorResponse(
                sensor.sensorId(),
                sensor.sensorKey(),
                sensor.sensorName(),
                sensor.measurementType(),
                sensor.unit(),
                thresholdRules);
    }

    public record AssetRow(UUID id, String assetCode, String name) {}

    private record SensorConfigurationRow(
            UUID sensorId,
            String sensorKey,
            String sensorName,
            MeasurementType measurementType,
            MeasurementUnit unit,
            UUID ruleId,
            String ruleCode,
            String ruleName,
            String comparison,
            BigDecimal thresholdValue,
            Integer cooldownSeconds,
            Boolean enabled) {}
}
