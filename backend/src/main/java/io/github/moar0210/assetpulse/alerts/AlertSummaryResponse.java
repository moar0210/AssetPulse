package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.util.UUID;

public record AlertSummaryResponse(
        UUID id,
        AlertStatus status,
        long occurrenceCount,
        Instant lastOccurredAt,
        AlertContextResponse context) {}
