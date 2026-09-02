package io.github.moar0210.assetpulse.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LoginFailureLimiter {

    private static final int MAX_CONFIGURED_FAILURES = 100;
    private static final int MAX_CONFIGURED_ENTRIES = 1_000_000;
    private static final Duration MIN_WINDOW = Duration.ofSeconds(1);
    private static final Duration MAX_WINDOW = Duration.ofDays(1);

    private final int maxFailures;
    private final Duration window;
    private final int maxEntries;
    private final Clock clock;
    private final Map<FailureKey, FailureWindow> failures = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public LoginFailureLimiter(
            @Value("${assetpulse.security.login-rate-limit.max-failures}") int maxFailures,
            @Value("${assetpulse.security.login-rate-limit.window}") Duration window,
            @Value("${assetpulse.security.login-rate-limit.max-entries}") int maxEntries) {
        this(maxFailures, window, maxEntries, Clock.systemUTC());
    }

    LoginFailureLimiter(int maxFailures, Duration window, int maxEntries, Clock clock) {
        if (maxFailures < 1 || maxFailures > MAX_CONFIGURED_FAILURES) {
            throw new IllegalArgumentException("Login max failures must be from 1 to 100");
        }
        if (window == null
                || window.compareTo(MIN_WINDOW) < 0
                || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "Login failure window must be from 1 second to 1 day");
        }
        if (maxEntries < 1 || maxEntries > MAX_CONFIGURED_ENTRIES) {
            throw new IllegalArgumentException("Login limiter entries must be from 1 to 1000000");
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    synchronized Decision check(String email, String clientAddress) {
        FailureKey key = key(email, clientAddress);
        Instant now = clock.instant();
        FailureWindow current = failures.get(key);
        if (current == null) {
            return Decision.allowed();
        }
        if (!now.isBefore(current.expiresAt())) {
            failures.remove(key);
            return Decision.allowed();
        }
        if (current.count() < maxFailures) {
            return Decision.allowed();
        }
        return Decision.blocked(retryAfterSeconds(now, current.expiresAt()));
    }

    synchronized void recordFailure(String email, String clientAddress) {
        FailureKey key = key(email, clientAddress);
        Instant now = clock.instant();
        FailureWindow current = failures.get(key);
        if (current != null && now.isBefore(current.expiresAt())) {
            failures.put(
                    key,
                    new FailureWindow(
                            Math.min(maxFailures, current.count() + 1), current.expiresAt()));
            return;
        }

        failures.remove(key);
        if (failures.size() >= maxEntries) {
            Iterator<FailureKey> oldest = failures.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        failures.put(key, new FailureWindow(1, now.plus(window)));
    }

    synchronized void clear(String email, String clientAddress) {
        failures.remove(key(email, clientAddress));
    }

    synchronized int trackedKeyCount() {
        return failures.size();
    }

    private FailureKey key(String email, String clientAddress) {
        return new FailureKey(
                email.strip().toLowerCase(Locale.ROOT),
                clientAddress.strip().toLowerCase(Locale.ROOT));
    }

    private long retryAfterSeconds(Instant now, Instant expiresAt) {
        long remainingMillis = Duration.between(now, expiresAt).toMillis();
        return Math.max(1, ((remainingMillis - 1) / 1000) + 1);
    }

    record Decision(boolean blocked, long retryAfterSeconds) {

        private static Decision allowed() {
            return new Decision(false, 0);
        }

        private static Decision blocked(long retryAfterSeconds) {
            return new Decision(true, retryAfterSeconds);
        }
    }

    private record FailureKey(String email, String clientAddress) {}

    private record FailureWindow(int count, Instant expiresAt) {}
}
