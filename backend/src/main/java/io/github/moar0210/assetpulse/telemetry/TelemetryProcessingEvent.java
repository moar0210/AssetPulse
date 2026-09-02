package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryProcessingEvent(
        UUID id,
        UUID organisationId,
        UUID telemetryBatchId,
        String eventType,
        Instant createdAt,
        String traceParent,
        String traceState) {}
