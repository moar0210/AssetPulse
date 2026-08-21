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
import org.springframework.transaction.annotation.Transactional;

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

    private static final String LOCK_THRESHOLD_RULE =
            """
            SELECT id
            FROM threshold_rule
            WHERE organisation_id = :organisationId
              AND id = :thresholdRuleId
            FOR UPDATE
            """;

    private static final String RECORD_OCCURRENCE =
            """
            WITH locked_alert AS MATERIALIZED (
                SELECT
                    alert.id,
                    alert.status,
                    alert.cooldown_until
                FROM alert
                WHERE alert.organisation_id = :organisationId
                  AND alert.threshold_rule_id = :thresholdRuleId
                  AND alert.fingerprint = :fingerprint
                ORDER BY
                    CASE
                        WHEN alert.status IN ('OPEN', 'ACKNOWLEDGED') THEN 0
                        ELSE 1
                    END,
                    alert.cooldown_until DESC,
                    alert.created_at DESC,
                    alert.id
                FOR UPDATE
                LIMIT 1
            ),
            updated_existing AS (
                UPDATE alert current_alert
                SET occurrence_count = current_alert.occurrence_count + 1,
                    first_occurred_at = LEAST(
                        current_alert.first_occurred_at,
                        :occurredAt
                    ),
                    last_occurred_at = GREATEST(
                        current_alert.last_occurred_at,
                        :occurredAt
                    ),
                    cooldown_until = CASE
                        WHEN :occurredAt >= current_alert.cooldown_until
                            THEN :cooldownUntil
                        ELSE current_alert.cooldown_until
                    END,
                    created_at = LEAST(current_alert.created_at, :effectAt),
                    updated_at = GREATEST(current_alert.updated_at, :effectAt)
                FROM locked_alert
                WHERE current_alert.id = locked_alert.id
                  AND current_alert.organisation_id = :organisationId
                  AND current_alert.threshold_rule_id = :thresholdRuleId
                  AND (
                      locked_alert.status IN ('OPEN', 'ACKNOWLEDGED')
                      OR (
                          locked_alert.status = 'RESOLVED'
                          AND :occurredAt < locked_alert.cooldown_until
                      )
                  )
                RETURNING current_alert.id
            ),
            upserted_active AS (
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
                SELECT
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
                WHERE NOT EXISTS (SELECT 1 FROM updated_existing)
                ON CONFLICT (organisation_id, fingerprint)
                WHERE status IN ('OPEN', 'ACKNOWLEDGED')
                DO UPDATE
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
                RETURNING alert.id
            )
            SELECT id FROM updated_existing
            UNION ALL
            SELECT id FROM upserted_active
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

    @Transactional
    public void recordOccurrence(
            UUID id,
            UUID organisationId,
            UUID thresholdRuleId,
            String fingerprint,
            Instant occurredAt,
            Instant cooldownUntil,
            Instant effectAt) {
        boolean ruleLocked =
                jdbcClient
                        .sql(LOCK_THRESHOLD_RULE)
                        .param("organisationId", organisationId)
                        .param("thresholdRuleId", thresholdRuleId)
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!ruleLocked) {
            throw new IllegalStateException("Tenant-owned threshold rule could not be locked");
        }

        boolean recorded =
                jdbcClient
                        .sql(RECORD_OCCURRENCE)
                        .param("id", id)
                        .param("organisationId", organisationId)
                        .param("thresholdRuleId", thresholdRuleId)
                        .param("fingerprint", fingerprint)
                        .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                        .param("cooldownUntil", cooldownUntil.atOffset(ZoneOffset.UTC))
                        .param("effectAt", effectAt.atOffset(ZoneOffset.UTC))
                        .query(UUID.class)
                        .optional()
                        .isPresent();
        if (!recorded) {
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
