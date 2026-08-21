package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AlertDetailResponse(
        UUID id,
        AlertStatus status,
        long occurrenceCount,
        Instant firstOccurredAt,
        Instant lastOccurredAt,
        Instant cooldownUntil,
        Instant createdAt,
        Instant updatedAt,
        AlertContextResponse context,
        List<AlertHistoryResponse> history) {

    public AlertDetailResponse {
        history = List.copyOf(history);
    }
}
