package io.github.moar0210.assetpulse.alerts;

import java.util.Objects;
import java.util.UUID;

public record AlertChangeEvent(UUID alertId, AlertChangeType changeType) {

    public AlertChangeEvent {
        Objects.requireNonNull(alertId);
        Objects.requireNonNull(changeType);
    }
}
