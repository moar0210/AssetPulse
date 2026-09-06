package io.github.moar0210.assetpulse.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PrivacySafeSpanExporterTest {

    @Test
    void exportsOnlyExplicitlySanitizedTelemetrySpans() {
        SpanExporter delegate = mock(SpanExporter.class);
        when(delegate.export(any())).thenReturn(CompletableResultCode.ofSuccess());
        PrivacySafeSpanExporter exporter = new PrivacySafeSpanExporter(delegate);
        SpanData serverSpan = span("POST /api/v1/assets/secret-object-id");
        SpanData acceptanceSpan = span("assetpulse.telemetry.accept");
        SpanData processingSpan = span("assetpulse.telemetry.process");

        CompletableResultCode result =
                exporter.export(List.of(serverSpan, acceptanceSpan, processingSpan));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<SpanData>> exported = ArgumentCaptor.forClass(Collection.class);
        verify(delegate).export(exported.capture());
        assertThat(exported.getValue()).containsExactly(acceptanceSpan, processingSpan);
    }

    private static SpanData span(String name) {
        SpanData span = mock(SpanData.class);
        when(span.getName()).thenReturn(name);
        return span;
    }
}
