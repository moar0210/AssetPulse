package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryProcessingPolicyTest {

    private final TelemetryProcessingPolicy policy = new TelemetryProcessingPolicy();

    @Test
    void definesTheLeaseAttemptBoundAndRetrySchedule() {
        assertThat(policy.maxAttempts()).isEqualTo(5);
        assertThat(policy.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(
                        List.of(
                                policy.retryDelay(1),
                                policy.retryDelay(2),
                                policy.retryDelay(3),
                                policy.retryDelay(4)))
                .containsExactly(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(5));
    }

    @Test
    void rejectsRetryDelaysOutsideNonFinalFailedAttempts() {
        for (int failedAttemptCount : List.of(-1, 0, 5, 6)) {
            assertThatThrownBy(() -> policy.retryDelay(failedAttemptCount))
                    .as("failed attempt count %s", failedAttemptCount)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Retry delay requires a non-final failed attempt");
        }
    }
}
