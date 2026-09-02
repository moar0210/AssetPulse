package io.github.moar0210.assetpulse.observability;

import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "assetpulse.observability.traces",
            name = "console-export-enabled",
            havingValue = "true",
            matchIfMissing = true)
    SpanExporter otlpJsonLoggingSpanExporter() {
        return new PrivacySafeSpanExporter(OtlpJsonLoggingSpanExporter.create());
    }
}
