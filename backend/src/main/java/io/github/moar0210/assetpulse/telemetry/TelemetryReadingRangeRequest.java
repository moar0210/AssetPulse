package io.github.moar0210.assetpulse.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public record TelemetryReadingRangeRequest(Instant from, Instant to, int limit) {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    private static final Duration MAX_SPAN = Duration.ofHours(24);

    public TelemetryReadingRangeRequest {
        if (from == null
                || to == null
                || !from.isBefore(to)
                || Duration.between(from, to).compareTo(MAX_SPAN) > 0
                || limit < 1
                || limit > MAX_LIMIT) {
            throw new InvalidTelemetryRangeException();
        }
    }

    public static TelemetryReadingRangeRequest fromQuery(
            String fromValue, String toValue, String limitValue) {
        if (fromValue == null || toValue == null) {
            throw new InvalidTelemetryRangeException();
        }

        try {
            Instant from = Instant.parse(fromValue);
            Instant to = Instant.parse(toValue);
            int limit = limitValue == null ? DEFAULT_LIMIT : Integer.parseInt(limitValue);
            return new TelemetryReadingRangeRequest(from, to, limit);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new InvalidTelemetryRangeException();
        }
    }
}
