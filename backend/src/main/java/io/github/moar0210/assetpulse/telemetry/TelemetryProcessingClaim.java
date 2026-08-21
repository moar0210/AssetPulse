package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryProcessingClaim(
        TelemetryProcessingEvent event,
        UUID claimToken,
        int attemptCount,
        Instant leaseExpiresAt) {}
