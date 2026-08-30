package io.github.moar0210.assetpulse.workorders;

import java.time.Instant;
import java.util.UUID;

public record WorkOrderHistoryResponse(
        int sequenceNumber,
        WorkOrderStatus fromStatus,
        WorkOrderStatus toStatus,
        ActorResponse actor,
        Instant transitionedAt) {

    public record ActorResponse(UUID id, String displayName) {}
}
