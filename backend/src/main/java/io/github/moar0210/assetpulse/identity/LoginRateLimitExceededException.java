package io.github.moar0210.assetpulse.identity;

public class LoginRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public LoginRateLimitExceededException(long retryAfterSeconds) {
        super("Login rate limit exceeded");
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("Retry-After must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
