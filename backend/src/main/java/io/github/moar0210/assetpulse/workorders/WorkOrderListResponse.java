package io.github.moar0210.assetpulse.workorders;

import java.util.List;

public record WorkOrderListResponse(List<WorkOrderResponse> workOrders, int limit) {

    public WorkOrderListResponse {
        workOrders = List.copyOf(workOrders);
    }
}
