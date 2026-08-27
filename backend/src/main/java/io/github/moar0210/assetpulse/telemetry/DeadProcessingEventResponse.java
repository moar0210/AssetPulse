package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;

public record DeadProcessingEventResponse(
        UUID id,
        UUID telemetryBatchId,
        String eventType,
        int attemptCount,
        Instant createdAt,
        Instant deadAt,
        Instant updatedAt,
        String lastErrorCode,
        String lastErrorMessage) {}
