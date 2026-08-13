package io.github.moar0210.assetpulse.telemetry;

public class TelemetryIdempotencyConflictException extends RuntimeException {

    public TelemetryIdempotencyConflictException() {
        super("The idempotency key is already associated with another request");
    }
}
