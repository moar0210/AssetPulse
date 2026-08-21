package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TelemetryReadingRepository {

    private static final String FIND_SENSOR_BY_ORGANISATION_AND_ID =
            """
            SELECT id
            FROM sensor
            WHERE organisation_id = :organisationId
              AND id = :sensorId
            """;

    private static final String FIND_MOST_RECENT_IN_RANGE =
            """
            SELECT id, value, observed_at
            FROM (
                SELECT id, value, observed_at
                FROM telemetry_reading
                WHERE organisation_id = :organisationId
                  AND sensor_id = :sensorId
                  AND observed_at >= :from
                  AND observed_at < :to
                ORDER BY observed_at DESC, id DESC
                LIMIT :limit
            ) recent_readings
            ORDER BY observed_at, id
            """;

    private final JdbcClient jdbcClient;

    public TelemetryReadingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean sensorExistsByOrganisationIdAndId(UUID organisationId, UUID sensorId) {
        return jdbcClient
                .sql(FIND_SENSOR_BY_ORGANISATION_AND_ID)
                .param("organisationId", organisationId)
                .param("sensorId", sensorId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    public List<TelemetryReadingRangeResponse.Reading> findMostRecentInRange(
            UUID organisationId, UUID sensorId, Instant from, Instant to, int limit) {
        return jdbcClient
                .sql(FIND_MOST_RECENT_IN_RANGE)
                .param("organisationId", organisationId)
                .param("sensorId", sensorId)
                .param("from", from.atOffset(ZoneOffset.UTC))
                .param("to", to.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query(
                        (resultSet, rowNumber) ->
                                new TelemetryReadingRangeResponse.Reading(
                                        resultSet.getObject("id", UUID.class),
                                        resultSet.getBigDecimal("value"),
                                        resultSet
                                                .getObject("observed_at", OffsetDateTime.class)
                                                .toInstant()))
                .list();
    }
}
