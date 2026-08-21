package io.github.moar0210.assetpulse.alerts;

import java.util.Objects;

public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED;

    public boolean canTransitionTo(AlertStatus target) {
        Objects.requireNonNull(target);
        return this == OPEN && target == ACKNOWLEDGED || this == ACKNOWLEDGED && target == RESOLVED;
    }
}
