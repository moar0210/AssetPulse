package io.github.moar0210.assetpulse.workorders;

import java.time.Instant;
import java.util.UUID;

public record WorkOrderResponse(
        UUID id,
        UUID alertId,
        WorkOrderStatus status,
        long version,
        AssignedTechnicianResponse assignedTechnician,
        Instant createdAt,
        Instant updatedAt,
        WorkOrderContextResponse context) {}
