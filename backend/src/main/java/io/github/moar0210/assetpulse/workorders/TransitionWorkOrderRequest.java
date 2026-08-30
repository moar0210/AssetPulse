package io.github.moar0210.assetpulse.workorders;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record TransitionWorkOrderRequest(
        @NotNull @PositiveOrZero @JsonDeserialize(using = StrictLongJsonDeserializer.class)
                Long expectedVersion) {}
