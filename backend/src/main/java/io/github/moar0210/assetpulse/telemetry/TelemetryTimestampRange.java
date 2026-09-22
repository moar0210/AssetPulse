package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;

final class TelemetryTimestampRange {

    private static final Instant MINIMUM = Instant.parse("0000-01-01T00:00:00Z");
    private static final Instant END_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    private TelemetryTimestampRange() {}

    static boolean contains(Instant value) {
        return value != null && !value.isBefore(MINIMUM) && value.isBefore(END_EXCLUSIVE);
    }
}
