package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryProcessingMetricsRepository {

    private static final String READ_SNAPSHOT =
            """
            SELECT
                COUNT(*) FILTER (WHERE status = 'PENDING')::bigint AS pending_count,
                GREATEST(
                    0,
                    COALESCE(
                        EXTRACT(
                            EPOCH FROM (
                                CAST(:observedAt AS TIMESTAMP WITH TIME ZONE)
                                - MIN(created_at) FILTER (
                                    WHERE status IN ('PENDING', 'PROCESSING')
                                )
                            )
                        ),
                        0
                    )
                )::double precision AS processing_lag_seconds,
                COUNT(*) FILTER (
                    WHERE status = 'PENDING'
                      AND attempt_count > 0
                )::bigint AS retrying_count,
                COUNT(*) FILTER (WHERE status = 'DEAD')::bigint AS dead_count
            FROM telemetry_processing_event
            """;

    private final JdbcClient jdbcClient;

    public TelemetryProcessingMetricsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public TelemetryProcessingMetricsSnapshot readSnapshot(Instant observedAt) {
        Objects.requireNonNull(observedAt);
        return jdbcClient
                .sql(READ_SNAPSHOT)
                .param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
                .query(
                        (resultSet, rowNumber) ->
                                new TelemetryProcessingMetricsSnapshot(
                                        resultSet.getLong("pending_count"),
                                        resultSet.getDouble("processing_lag_seconds"),
                                        resultSet.getLong("retrying_count"),
                                        resultSet.getLong("dead_count"),
                                        observedAt))
                .single();
    }
}
