package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryBatchResponse(
        UUID batchId, String idempotencyKey, int readingCount, Instant acceptedAt) {}
