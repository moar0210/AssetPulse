package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryProcessingEventRepository {

    private static final String INSERT_ACCEPTED_EVENT =
            """
            INSERT INTO telemetry_processing_event (
                id,
                organisation_id,
                telemetry_batch_id,
                event_type,
                created_at
            )
            VALUES (
                :id,
                :organisationId,
                :telemetryBatchId,
                'TELEMETRY_BATCH_ACCEPTED',
                :createdAt
            )
            """;

    private final JdbcClient jdbcClient;

    public TelemetryProcessingEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertBatchAccepted(UUID organisationId, UUID telemetryBatchId, Instant createdAt) {
        jdbcClient
                .sql(INSERT_ACCEPTED_EVENT)
                .param("id", UUID.randomUUID())
                .param("organisationId", organisationId)
                .param("telemetryBatchId", telemetryBatchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }
}
