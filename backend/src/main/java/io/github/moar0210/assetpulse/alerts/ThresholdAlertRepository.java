package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.assets.ThresholdComparison;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ThresholdAlertRepository {

    private static final String FIND_EVALUATIONS =
            """
            SELECT
                rule.id AS threshold_rule_id,
                rule.comparison,
                rule.threshold_value,
                rule.cooldown_seconds,
                reading.value,
                reading.observed_at
            FROM telemetry_reading reading
            JOIN threshold_rule rule
              ON rule.organisation_id = reading.organisation_id
             AND rule.sensor_id = reading.sensor_id
            WHERE reading.organisation_id = :organisationId
              AND reading.batch_id = :telemetryBatchId
              AND rule.enabled
            ORDER BY
                rule.id,
                reading.observed_at,
                reading.sequence_number,
                reading.id
            """;

    private static final String RECORD_OCCURRENCE =
            """
            INSERT INTO alert (
                id,
                organisation_id,
                threshold_rule_id,
                fingerprint,
                status,
                occurrence_count,
                first_occurred_at,
                last_occurred_at,
                cooldown_until,
                created_at,
                updated_at
            )
            VALUES (
                :id,
                :organisationId,
                :thresholdRuleId,
                :fingerprint,
                'OPEN',
                1,
                :occurredAt,
                :occurredAt,
                :cooldownUntil,
                :effectAt,
                :effectAt
            )
            ON CONFLICT ON CONSTRAINT uq_alert_organisation_fingerprint DO UPDATE
            SET occurrence_count = alert.occurrence_count + 1,
                first_occurred_at = LEAST(
                    alert.first_occurred_at,
                    EXCLUDED.first_occurred_at
                ),
                last_occurred_at = GREATEST(
                    alert.last_occurred_at,
                    EXCLUDED.last_occurred_at
                ),
                cooldown_until = CASE
                    WHEN EXCLUDED.last_occurred_at >= alert.cooldown_until
                        THEN EXCLUDED.cooldown_until
                    ELSE alert.cooldown_until
                END,
                created_at = LEAST(alert.created_at, EXCLUDED.created_at),
                updated_at = GREATEST(alert.updated_at, EXCLUDED.updated_at)
            WHERE alert.threshold_rule_id = EXCLUDED.threshold_rule_id
            """;

    private final JdbcClient jdbcClient;

    public ThresholdAlertRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ThresholdEvaluation> findEvaluations(UUID organisationId, UUID telemetryBatchId) {
        return jdbcClient
                .sql(FIND_EVALUATIONS)
                .param("organisationId", organisationId)
                .param("telemetryBatchId", telemetryBatchId)
                .query(ThresholdAlertRepository::mapEvaluation)
                .list();
    }

    public void recordOccurrence(
            UUID id,
            UUID organisationId,
            UUID thresholdRuleId,
            String fingerprint,
            Instant occurredAt,
            Instant cooldownUntil,
            Instant effectAt) {
        int updated =
                jdbcClient
                        .sql(RECORD_OCCURRENCE)
                        .param("id", id)
                        .param("organisationId", organisationId)
                        .param("thresholdRuleId", thresholdRuleId)
                        .param("fingerprint", fingerprint)
                        .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                        .param("cooldownUntil", cooldownUntil.atOffset(ZoneOffset.UTC))
                        .param("effectAt", effectAt.atOffset(ZoneOffset.UTC))
                        .update();
        if (updated != 1) {
            throw new IllegalStateException("Threshold alert occurrence was not recorded");
        }
    }

    private static ThresholdEvaluation mapEvaluation(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ThresholdEvaluation(
                resultSet.getObject("threshold_rule_id", UUID.class),
                ThresholdComparison.valueOf(resultSet.getString("comparison")),
                resultSet.getBigDecimal("threshold_value"),
                resultSet.getInt("cooldown_seconds"),
                resultSet.getBigDecimal("value"),
                resultSet.getObject("observed_at", OffsetDateTime.class).toInstant());
    }

    public record ThresholdEvaluation(
            UUID thresholdRuleId,
            ThresholdComparison comparison,
            BigDecimal thresholdValue,
            int cooldownSeconds,
            BigDecimal value,
            Instant observedAt) {}
}
