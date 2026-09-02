package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.Objects;

public record TelemetryProcessingMetricsSnapshot(
        long pendingCount,
        double processingLagSeconds,
        long retryingCount,
        long deadCount,
        Instant observedAt) {

    public TelemetryProcessingMetricsSnapshot {
        if (pendingCount < 0 || retryingCount < 0 || deadCount < 0) {
            throw new IllegalArgumentException("Telemetry processing counts cannot be negative");
        }
        if (!Double.isFinite(processingLagSeconds) || processingLagSeconds < 0) {
            throw new IllegalArgumentException(
                    "Telemetry processing lag must be finite and non-negative");
        }
        Objects.requireNonNull(observedAt);
    }
}
