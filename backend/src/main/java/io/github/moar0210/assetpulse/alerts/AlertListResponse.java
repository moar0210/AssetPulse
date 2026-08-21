package io.github.moar0210.assetpulse.alerts;

import java.util.List;

public record AlertListResponse(List<AlertSummaryResponse> alerts, int limit) {

    public AlertListResponse {
        alerts = List.copyOf(alerts);
    }
}
