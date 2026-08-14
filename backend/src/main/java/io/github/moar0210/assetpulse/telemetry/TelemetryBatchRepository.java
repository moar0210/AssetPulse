package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryBatchRepository {

    private static final String FIND_OWNED_SENSORS =
            """
            SELECT id
            FROM sensor
            WHERE organisation_id = :organisationId
              AND id IN (:sensorIds)
            """;

    private static final String INSERT_BATCH =
            """
            INSERT INTO telemetry_batch (
                id,
                organisation_id,
                idempotency_key,
                request_fingerprint,
                reading_count,
                accepted_at
            )
            VALUES (
                :id,
                :organisationId,
                :idempotencyKey,
                :requestFingerprint,
                :readingCount,
                :acceptedAt
            )
            ON CONFLICT (organisation_id, idempotency_key) DO NOTHING
            RETURNING id, idempotency_key, request_fingerprint, reading_count, accepted_at
            """;

    private static final String FIND_BATCH_BY_KEY =
            """
            SELECT id, idempotency_key, request_fingerprint, reading_count, accepted_at
            FROM telemetry_batch
            WHERE organisation_id = :organisationId
              AND idempotency_key = :idempotencyKey
            """;

    private static final String INSERT_READING =
            """
            INSERT INTO telemetry_reading (
                id,
                organisation_id,
                batch_id,
                sequence_number,
                sensor_id,
                value,
                observed_at
            )
            VALUES (
                :id,
                :organisationId,
                :batchId,
                :sequenceNumber,
                :sensorId,
                :value,
                :observedAt
            )
            """;

    private final JdbcClient jdbcClient;

    public TelemetryBatchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Set<UUID> findOwnedSensorIds(UUID organisationId, Set<UUID> sensorIds) {
        return jdbcClient
                .sql(FIND_OWNED_SENSORS)
                .param("organisationId", organisationId)
                .param("sensorIds", sensorIds)
                .query(UUID.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<BatchRow> tryCreate(
            UUID id,
            UUID organisationId,
            String idempotencyKey,
            String requestFingerprint,
            int readingCount,
            Instant acceptedAt) {
        return jdbcClient
                .sql(INSERT_BATCH)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", requestFingerprint)
                .param("readingCount", readingCount)
                .param("acceptedAt", acceptedAt.atOffset(ZoneOffset.UTC))
                .query(TelemetryBatchRepository::mapBatch)
                .optional();
    }

    public BatchRow findByOrganisationIdAndIdempotencyKey(
            UUID organisationId, String idempotencyKey) {
        return jdbcClient
                .sql(FIND_BATCH_BY_KEY)
                .param("organisationId", organisationId)
                .param("idempotencyKey", idempotencyKey)
                .query(TelemetryBatchRepository::mapBatch)
                .single();
    }

    public void insertReadings(
            UUID organisationId, UUID batchId, List<TelemetryBatchRequest.Reading> readings) {
        for (int sequence = 0; sequence < readings.size(); sequence++) {
            TelemetryBatchRequest.Reading reading = readings.get(sequence);
            jdbcClient
                    .sql(INSERT_READING)
                    .param("id", UUID.randomUUID())
                    .param("organisationId", organisationId)
                    .param("batchId", batchId)
                    .param("sequenceNumber", sequence)
                    .param("sensorId", reading.sensorId())
                    .param("value", reading.value())
                    .param("observedAt", reading.observedAt().atOffset(ZoneOffset.UTC))
                    .update();
        }
    }

    private static BatchRow mapBatch(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new BatchRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_fingerprint"),
                resultSet.getInt("reading_count"),
                resultSet.getObject("accepted_at", OffsetDateTime.class).toInstant());
    }

    public record BatchRow(
            UUID id,
            String idempotencyKey,
            String requestFingerprint,
            int readingCount,
            Instant acceptedAt) {}
}
