package io.github.moar0210.assetpulse.workorders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkOrderDetailResponse(
        UUID id,
        UUID alertId,
        WorkOrderStatus status,
        long version,
        AssignedTechnicianResponse assignedTechnician,
        Instant createdAt,
        Instant updatedAt,
        WorkOrderContextResponse context,
        List<WorkOrderHistoryResponse> history) {

    public WorkOrderDetailResponse {
        history = List.copyOf(history);
    }

    public static WorkOrderDetailResponse from(
            WorkOrderResponse summary, List<WorkOrderHistoryResponse> history) {
        return new WorkOrderDetailResponse(
                summary.id(),
                summary.alertId(),
                summary.status(),
                summary.version(),
                summary.assignedTechnician(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.context(),
                history);
    }
}
