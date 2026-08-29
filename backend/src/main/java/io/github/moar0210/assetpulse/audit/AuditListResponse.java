package io.github.moar0210.assetpulse.audit;

import java.util.List;

public record AuditListResponse(List<AuditEventResponse> events, int limit) {
    public AuditListResponse {
        events = List.copyOf(events);
    }
}
