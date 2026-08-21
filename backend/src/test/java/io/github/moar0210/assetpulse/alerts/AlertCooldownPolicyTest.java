package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertCooldownPolicyTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-21T10:00:00Z");

    private final AlertCooldownPolicy policy = new AlertCooldownPolicy();

    @Test
    void calculatesDeadlinesAtBothSupportedBounds() {
        assertThat(policy.deadline(OCCURRED_AT, 0)).isEqualTo(OCCURRED_AT);
        assertThat(policy.deadline(OCCURRED_AT, 604800))
                .isEqualTo(OCCURRED_AT.plus(Duration.ofDays(7)));
    }

    @Test
    void rejectsCooldownsOutsideTheSupportedBounds() {
        for (int cooldownSeconds : List.of(-1, 604801)) {
            assertThatThrownBy(() -> policy.deadline(OCCURRED_AT, cooldownSeconds))
                    .as("cooldown seconds %s", cooldownSeconds)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Cooldown must be between zero and seven days");
        }
    }

    @Test
    void treatsTheCooldownAsAHalfOpenInterval() {
        Instant cooldownUntil = policy.deadline(OCCURRED_AT, 300);

        assertThat(policy.isInside(cooldownUntil.minusNanos(1), cooldownUntil)).isTrue();
        assertThat(policy.isInside(cooldownUntil, cooldownUntil)).isFalse();
        assertThat(policy.isInside(cooldownUntil.plusNanos(1), cooldownUntil)).isFalse();
    }

    @Test
    void rejectsMissingCooldownTimes() {
        assertThatThrownBy(() -> policy.deadline(null, 300))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.isInside(null, OCCURRED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> policy.isInside(OCCURRED_AT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
