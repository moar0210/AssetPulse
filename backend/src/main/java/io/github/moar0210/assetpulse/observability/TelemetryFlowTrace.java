package io.github.moar0210.assetpulse.observability;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEvent;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingFailureDisposition;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class TelemetryFlowTrace {

    private static final Logger LOGGER = LoggerFactory.getLogger(TelemetryFlowTrace.class);
    private static final String TRACE_PARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TelemetryFlowTrace(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public DurableTraceContext captureCurrent() {
        Span currentSpan = tracer.currentSpan();
        if (currentSpan == null || currentSpan.isNoop()) {
            return DurableTraceContext.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(currentSpan.context(), carrier, Map::put);
        return new DurableTraceContext(carrier.get(TRACE_PARENT), null);
    }

    public Span startAcceptanceSpan() {
        return tracer.spanBuilder()
                .setNoParent()
                .name("assetpulse.telemetry.accept")
                .kind(Span.Kind.PRODUCER)
                .start();
    }

    public Span startProcessingSpan(TelemetryProcessingEvent event) {
        Span.Builder builder;
        if (event.traceParent() == null) {
            builder = tracer.spanBuilder().setNoParent();
        } else {
            Map<String, String> carrier = new HashMap<>();
            carrier.put(TRACE_PARENT, event.traceParent());
            builder = propagator.extract(carrier, Map::get);
        }
        return builder.name("assetpulse.telemetry.process")
                .kind(Span.Kind.CONSUMER)
                .tag("assetpulse.flow_id", event.id().toString())
                .tag("assetpulse.event_type", event.eventType())
                .start();
    }

    public Tracer.SpanInScope activate(Span span) {
        return tracer.withSpan(span);
    }

    public void acceptedAfterCommit(
            Span acceptanceSpan, UUID flowId, UUID organisationId, UUID telemetryBatchId) {
        acceptanceSpan.tag("assetpulse.flow_id", flowId.toString());
        acceptanceSpan.tag("assetpulse.event_type", "TELEMETRY_BATCH_ACCEPTED");
        Runnable acceptedLog =
                () -> {
                    try (Tracer.SpanInScope ignored = activate(acceptanceSpan)) {
                        LOGGER.atInfo()
                                .addKeyValue("event", "telemetry_flow")
                                .addKeyValue("trace_phase", "accepted")
                                .addKeyValue("flow_id", flowId)
                                .addKeyValue("organisation_id", organisationId)
                                .addKeyValue("telemetry_batch_id", telemetryBatchId)
                                .log("Telemetry batch accepted durably");
                    }
                };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            acceptedLog.run();
            acceptanceSpan.end();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        acceptedLog.run();
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            acceptanceSpan.error(
                                    new IllegalStateException(
                                            "Telemetry acceptance transaction did not commit"));
                        }
                        acceptanceSpan.end();
                    }
                });
    }

    public void processingStarted(TelemetryProcessingEvent event, int attemptCount) {
        LOGGER.atInfo()
                .addKeyValue("event", "telemetry_flow")
                .addKeyValue("trace_phase", "processing_started")
                .addKeyValue("flow_id", event.id())
                .addKeyValue("organisation_id", event.organisationId())
                .addKeyValue("telemetry_batch_id", event.telemetryBatchId())
                .addKeyValue("attempt_count", attemptCount)
                .log("Telemetry processing started");
    }

    public void alertRecordedAfterCommit(TelemetryProcessingEvent event, UUID alertId) {
        afterCommit(
                () ->
                        LOGGER.atInfo()
                                .addKeyValue("event", "telemetry_flow")
                                .addKeyValue("trace_phase", "alert_recorded")
                                .addKeyValue("flow_id", event.id())
                                .addKeyValue("organisation_id", event.organisationId())
                                .addKeyValue("telemetry_batch_id", event.telemetryBatchId())
                                .addKeyValue("alert_id", alertId)
                                .log("Alert occurrence recorded durably"));
    }

    public void processingCompleted(TelemetryProcessingEvent event) {
        LOGGER.atInfo()
                .addKeyValue("event", "telemetry_flow")
                .addKeyValue("trace_phase", "processing_completed")
                .addKeyValue("flow_id", event.id())
                .addKeyValue("organisation_id", event.organisationId())
                .addKeyValue("telemetry_batch_id", event.telemetryBatchId())
                .log("Telemetry processing completed durably");
    }

    public void processingStale(TelemetryProcessingEvent event) {
        LOGGER.atWarn()
                .addKeyValue("event", "telemetry_flow")
                .addKeyValue("trace_phase", "stale_claim")
                .addKeyValue("flow_id", event.id())
                .addKeyValue("organisation_id", event.organisationId())
                .log("Telemetry processing claim was stale");
    }

    public void processingFailed(
            TelemetryProcessingEvent event, TelemetryProcessingFailureDisposition disposition) {
        String tracePhase =
                switch (disposition) {
                    case RETRY_SCHEDULED -> "retry_scheduled";
                    case DEAD -> "dead";
                    case STALE_CLAIM -> "stale_claim";
                };
        LOGGER.atWarn()
                .addKeyValue("event", "telemetry_flow")
                .addKeyValue("trace_phase", tracePhase)
                .addKeyValue("flow_id", event.id())
                .addKeyValue("organisation_id", event.organisationId())
                .addKeyValue("failure_disposition", disposition)
                .log("Telemetry processing attempt failed safely");
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}
