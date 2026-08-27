package io.github.moar0210.assetpulse.workorders;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateWorkOrderRequest(@NotNull UUID alertId) {}
