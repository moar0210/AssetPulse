package io.github.moar0210.assetpulse.workorders;

import java.util.UUID;

public record WorkOrderContextResponse(
        UUID assetId, String assetCode, String assetName, String ruleName) {}
