package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "assetpulse.telemetry.processing",
        name = "enabled",
        havingValue = "true")
public class TelemetryProcessingWorker {

    private final TelemetryProcessingLifecycleService lifecycleService;
    private final TelemetryProcessingExecutionService executionService;
    private final TelemetryProcessingEventHandler handler;
    private final String claimOwner = "worker-" + UUID.randomUUID();

    public TelemetryProcessingWorker(
            TelemetryProcessingLifecycleService lifecycleService,
            TelemetryProcessingExecutionService executionService,
            TelemetryProcessingEventHandler handler) {
        this.lifecycleService = lifecycleService;
        this.executionService = executionService;
        this.handler = handler;
    }

    @Scheduled(
            initialDelayString = "${assetpulse.telemetry.processing.initial-delay-millis:1000}",
            fixedDelayString = "${assetpulse.telemetry.processing.poll-delay-millis:1000}")
    public void poll() {
        lifecycleService
                .claimNext(claimOwner, Instant.now())
                .ifPresent(
                        claim -> {
                            try {
                                executionService.execute(claim, handler);
                            } catch (RuntimeException processingFailure) {
                                lifecycleService.recordFailure(claim, Instant.now());
                            }
                        });
    }
}
