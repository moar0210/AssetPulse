package io.github.moar0210.assetpulse.telemetry;

import java.util.List;

public record DeadProcessingEventListResponse(List<DeadProcessingEventResponse> events, int limit) {

    public DeadProcessingEventListResponse {
        events = List.copyOf(events);
    }
}
