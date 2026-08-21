package io.github.moar0210.assetpulse.telemetry;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelemetryProcessingPolicy {

    static final int MAX_ATTEMPTS = 5;

    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final List<Duration> RETRY_DELAYS =
            List.of(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(5));

    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    public Duration leaseDuration() {
        return LEASE_DURATION;
    }

    public Duration retryDelay(int failedAttemptCount) {
        if (failedAttemptCount < 1 || failedAttemptCount >= MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Retry delay requires a non-final failed attempt");
        }
        return RETRY_DELAYS.get(failedAttemptCount - 1);
    }
}
