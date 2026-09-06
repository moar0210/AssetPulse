package io.github.moar0210.assetpulse.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TelemetryFlowTraceTest {

    @Test
    void continuesTheAcceptanceTraceFromPersistedW3cContextAfterTheRequestSpanEnds() {
        CollectingSpanExporter exporter = new CollectingSpanExporter();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            ContextPropagators contextPropagators =
                    ContextPropagators.create(W3CTraceContextPropagator.getInstance());
            OpenTelemetry openTelemetry =
                    OpenTelemetrySdk.builder()
                            .setTracerProvider(provider)
                            .setPropagators(contextPropagators)
                            .build();
            io.opentelemetry.api.trace.Tracer otelTracer =
                    openTelemetry.getTracer("assetpulse-test");
            OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
            Tracer tracer = new OtelTracer(otelTracer, currentTraceContext, ignored -> {});
            TelemetryFlowTrace flowTrace =
                    new TelemetryFlowTrace(
                            tracer, new OtelPropagator(contextPropagators, otelTracer));

            String untrustedTraceId = "11111111111111111111111111111111";
            SpanContext untrustedParent =
                    SpanContext.createFromRemoteParent(
                            untrustedTraceId,
                            "2222222222222222",
                            TraceFlags.getSampled(),
                            TraceState.builder().put("vendor", "untrusted-request-state").build());
            Span acceptance;
            try (Scope ignored =
                    io.opentelemetry.api.trace.Span.wrap(untrustedParent).makeCurrent()) {
                acceptance = flowTrace.startAcceptanceSpan();
            }
            DurableTraceContext durableContext;
            String traceId = acceptance.context().traceId();
            assertThat(traceId).isNotEqualTo(untrustedTraceId);
            try (Tracer.SpanInScope ignored = tracer.withSpan(acceptance)) {
                durableContext = flowTrace.captureCurrent();
            }
            UUID flowId = UUID.randomUUID();
            UUID organisationId = UUID.randomUUID();
            UUID telemetryBatchId = UUID.randomUUID();
            flowTrace.acceptedAfterCommit(acceptance, flowId, organisationId, telemetryBatchId);

            assertThat(durableContext.traceParent()).matches("00-" + traceId + "-[0-9a-f]{16}-01");
            assertThat(durableContext.traceState()).isNull();
            TelemetryProcessingEvent event =
                    new TelemetryProcessingEvent(
                            flowId,
                            organisationId,
                            telemetryBatchId,
                            "TELEMETRY_BATCH_ACCEPTED",
                            Instant.parse("2026-09-01T12:00:00Z"),
                            durableContext.traceParent(),
                            "vendor=untrusted-request-state");
            Span processing = flowTrace.startProcessingSpan(event);
            try (Tracer.SpanInScope ignored = flowTrace.activate(processing)) {
                assertThat(processing.context().traceId()).isEqualTo(traceId);
                assertThat(processing.context().spanId())
                        .isNotEqualTo(acceptance.context().spanId());
            } finally {
                processing.end();
            }

            assertThat(exporter.spans())
                    .extracting(SpanData::getName)
                    .containsExactly("assetpulse.telemetry.accept", "assetpulse.telemetry.process");
            assertThat(exporter.spans()).extracting(SpanData::getTraceId).containsOnly(traceId);
            assertThat(exporter.spans())
                    .allSatisfy(
                            span ->
                                    assertThat(span.getSpanContext().getTraceState().isEmpty())
                                            .isTrue());
        }
    }

    @Test
    void marksTheAcceptanceSpanWithOnlyASanitizedErrorWhenTheTransactionRollsBack() {
        Tracer tracer = mock(Tracer.class);
        Propagator propagator = mock(Propagator.class);
        Span acceptance = mock(Span.class);
        TelemetryFlowTrace flowTrace = new TelemetryFlowTrace(tracer, propagator);
        TransactionSynchronizationManager.initSynchronization();
        try {
            flowTrace.acceptedAfterCommit(
                    acceptance, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager.getSynchronizations().getFirst();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(acceptance)
                    .error(
                            argThat(
                                    failure ->
                                            "Telemetry acceptance transaction did not commit"
                                                    .equals(failure.getMessage())));
            verify(acceptance).end();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static final class CollectingSpanExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<SpanData> spans() {
            return List.copyOf(spans);
        }
    }
}
