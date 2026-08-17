package io.github.moar0210.assetpulse.telemetry;

public enum TelemetryProcessingFailureDisposition {
    RETRY_SCHEDULED,
    DEAD,
    STALE_CLAIM
}
