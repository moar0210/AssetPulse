package io.github.moar0210.assetpulse.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

final class PrivacySafeSpanExporter implements SpanExporter {

    private static final Set<String> EXPORTABLE_SPAN_NAMES =
            Set.of("assetpulse.telemetry.accept", "assetpulse.telemetry.process");

    private final SpanExporter delegate;

    PrivacySafeSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        List<SpanData> privacySafeSpans =
                spans.stream()
                        .filter(span -> EXPORTABLE_SPAN_NAMES.contains(span.getName()))
                        .toList();
        if (privacySafeSpans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        return delegate.export(privacySafeSpans);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
