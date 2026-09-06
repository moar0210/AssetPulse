package io.github.moar0210.assetpulse.dashboard;

import java.util.List;

public record DashboardResponse(
        long assetCount,
        long openAlertCount,
        long activeWorkOrderCount,
        List<DashboardActivityResponse> recentActivity) {

    public DashboardResponse {
        recentActivity = List.copyOf(recentActivity);
    }
}
