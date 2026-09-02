package io.github.moar0210.assetpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginFailureLimiterTest {

    private static final Instant START = Instant.parse("2026-08-31T12:00:00Z");

    private MutableClock clock;
    private LoginFailureLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        limiter = new LoginFailureLimiter(2, Duration.ofSeconds(10), 2, clock);
    }

    @Test
    void blocksOnlyTheMatchingNormalizedEmailAndAddressWithAWholeSecondRetry() {
        limiter.recordFailure("  USER@example.test ", "203.0.113.10");
        limiter.recordFailure("user@EXAMPLE.test", "203.0.113.10");

        assertThat(limiter.check("user@example.test", "203.0.113.10"))
                .isEqualTo(new LoginFailureLimiter.Decision(true, 10));
        assertThat(limiter.check("other@example.test", "203.0.113.10").blocked()).isFalse();
        assertThat(limiter.check("user@example.test", "203.0.113.11").blocked()).isFalse();

        clock.advance(Duration.ofMillis(1500));

        assertThat(limiter.check("user@example.test", "203.0.113.10"))
                .isEqualTo(new LoginFailureLimiter.Decision(true, 9));
    }

    @Test
    void theWindowAndAnExplicitSuccessBothResetTheKey() {
        failTwice("user@example.test", "203.0.113.10");
        assertThat(limiter.check("user@example.test", "203.0.113.10").blocked()).isTrue();

        clock.advance(Duration.ofSeconds(10));

        assertThat(limiter.check("user@example.test", "203.0.113.10").blocked()).isFalse();
        assertThat(limiter.trackedKeyCount()).isZero();

        failTwice("user@example.test", "203.0.113.10");
        limiter.clear(" USER@example.test ", "203.0.113.10");

        assertThat(limiter.check("user@example.test", "203.0.113.10").blocked()).isFalse();
        assertThat(limiter.trackedKeyCount()).isZero();
    }

    @Test
    void theLeastRecentlyUsedKeyIsEvictedAtTheConfiguredBound() {
        failTwice("first@example.test", "203.0.113.10");
        failTwice("second@example.test", "203.0.113.10");

        limiter.recordFailure("third@example.test", "203.0.113.10");

        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
        assertThat(limiter.check("first@example.test", "203.0.113.10").blocked()).isFalse();
        assertThat(limiter.check("second@example.test", "203.0.113.10").blocked()).isTrue();
    }

    private void failTwice(String email, String clientAddress) {
        limiter.recordFailure(email, clientAddress);
        limiter.recordFailure(email, clientAddress);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
