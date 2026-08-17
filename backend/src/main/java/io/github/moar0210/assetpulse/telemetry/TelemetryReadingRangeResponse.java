package io.github.moar0210.assetpulse.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelemetryReadingRangeResponse(
        UUID sensorId, Instant from, Instant to, List<Reading> readings) {

    public TelemetryReadingRangeResponse {
        readings = List.copyOf(readings);
    }

    public record Reading(UUID id, BigDecimal value, Instant observedAt) {}
}
