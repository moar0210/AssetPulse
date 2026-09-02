package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.observability.TelemetryFlowTrace;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
    private final TelemetryFlowTrace flowTrace;
    private final String claimOwner = "worker-" + UUID.randomUUID();

    public TelemetryProcessingWorker(
            TelemetryProcessingLifecycleService lifecycleService,
            TelemetryProcessingExecutionService executionService,
            TelemetryProcessingEventHandler handler,
            TelemetryFlowTrace flowTrace) {
        this.lifecycleService = lifecycleService;
        this.executionService = executionService;
        this.handler = handler;
        this.flowTrace = flowTrace;
    }

    @Scheduled(
            initialDelayString = "${assetpulse.telemetry.processing.initial-delay-millis:1000}",
            fixedDelayString = "${assetpulse.telemetry.processing.poll-delay-millis:1000}")
    public void poll() {
        lifecycleService.claimNext(claimOwner, Instant.now()).ifPresent(this::process);
    }

    private void process(TelemetryProcessingClaim claim) {
        Span span = flowTrace.startProcessingSpan(claim.event());
        try (Tracer.SpanInScope ignored = flowTrace.activate(span)) {
            flowTrace.processingStarted(claim.event(), claim.attemptCount());
            try {
                if (executionService.execute(claim, handler)) {
                    flowTrace.processingCompleted(claim.event());
                } else {
                    flowTrace.processingStale(claim.event());
                }
            } catch (RuntimeException processingFailure) {
                span.error(new IllegalStateException("Telemetry processing failed"));
                TelemetryProcessingFailureDisposition disposition =
                        lifecycleService.recordFailure(claim, Instant.now());
                flowTrace.processingFailed(claim.event(), disposition);
            }
        } finally {
            span.end();
        }
    }
}
