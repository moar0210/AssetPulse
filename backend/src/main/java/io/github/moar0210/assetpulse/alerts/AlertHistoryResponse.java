package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.util.UUID;

public record AlertHistoryResponse(
        int sequenceNumber,
        AlertStatus fromStatus,
        AlertStatus toStatus,
        ActorResponse actor,
        Instant transitionedAt) {

    public record ActorResponse(UUID id, String displayName) {}
}
