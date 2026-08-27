package io.github.moar0210.assetpulse.workorders;

import java.util.List;

public record EligibleTechnicianListResponse(List<EligibleTechnicianResponse> technicians) {

    public EligibleTechnicianListResponse {
        technicians = List.copyOf(technicians);
    }
}
