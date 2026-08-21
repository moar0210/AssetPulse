package io.github.moar0210.assetpulse.telemetry;

@FunctionalInterface
public interface TelemetryProcessingEventHandler {

    void handle(TelemetryProcessingEvent event);
}
