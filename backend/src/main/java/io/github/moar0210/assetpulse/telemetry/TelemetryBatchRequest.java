package io.github.moar0210.assetpulse.telemetry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TelemetryBatchRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$")
                String idempotencyKey,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Reading> readings) {

    public TelemetryBatchRequest {
        readings = readings == null ? null : List.copyOf(readings);
    }

    public record Reading(
            @NotNull UUID sensorId,
            @NotNull
                    @Digits(integer = 13, fraction = 6)
                    @DecimalMin("-1000000000000.000000")
                    @DecimalMax("1000000000000.000000")
                    BigDecimal value,
            @NotNull @PastOrPresent Instant observedAt) {}
}
