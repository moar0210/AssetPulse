package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class AlertCooldownPolicy {

    public Instant deadline(Instant occurredAt, int cooldownSeconds) {
        Objects.requireNonNull(occurredAt);
        if (cooldownSeconds < 0 || cooldownSeconds > 604800) {
            throw new IllegalArgumentException("Cooldown must be between zero and seven days");
        }
        return occurredAt.plusSeconds(cooldownSeconds);
    }

    public boolean isInside(Instant occurredAt, Instant cooldownUntil) {
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(cooldownUntil);
        return occurredAt.isBefore(cooldownUntil);
    }
}
