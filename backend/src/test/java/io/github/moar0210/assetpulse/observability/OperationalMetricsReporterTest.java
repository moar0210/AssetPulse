package io.github.moar0210.assetpulse.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingMetricsRepository;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingMetricsSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class OperationalMetricsReporterTest {

    @Test
    void updatesFourLowCardinalityProcessingMetricsAndReportsHttpLatencyAndErrors() {
        TelemetryProcessingMetricsRepository repository =
                mock(TelemetryProcessingMetricsRepository.class);
        TelemetryProcessingMetricsSnapshot snapshot =
                new TelemetryProcessingMetricsSnapshot(
                        3, 42.5, 2, 1, Instant.parse("2026-09-01T12:00:00Z"));
        when(repository.readSnapshot(any())).thenReturn(snapshot);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            Timer.builder("http.server.requests")
                    .tag("status", "200")
                    .register(registry)
                    .record(Duration.ofMillis(25));
            Timer.builder("http.server.requests")
                    .tag("status", "503")
                    .register(registry)
                    .record(Duration.ofMillis(75));
            TelemetryProcessingMetrics metrics = new TelemetryProcessingMetrics(registry);
            OperationalMetricsReporter reporter =
                    new OperationalMetricsReporter(repository, metrics, registry);

            Logger logger = (Logger) LoggerFactory.getLogger(OperationalMetricsReporter.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                reporter.report();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertGauge(registry, "assetpulse.telemetry.processing.pending", 3.0);
            assertGauge(registry, "assetpulse.telemetry.processing.lag.seconds", 42.5);
            assertGauge(registry, "assetpulse.telemetry.processing.retrying", 2.0);
            assertGauge(registry, "assetpulse.telemetry.processing.dead", 1.0);
            assertThat(
                            registry.getMeters().stream()
                                    .filter(
                                            meter ->
                                                    meter.getId()
                                                            .getName()
                                                            .startsWith(
                                                                    "assetpulse.telemetry.processing"))
                                    .flatMap(meter -> meter.getId().getTags().stream()))
                    .isEmpty();

            assertThat(appender.list).hasSize(1);
            Map<String, Object> values =
                    appender.list.getFirst().getKeyValuePairs().stream()
                            .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(values)
                    .containsEntry("event", "operational_metrics")
                    .containsEntry("http_request_count", 2L)
                    .containsEntry("http_error_count", 1L)
                    .containsEntry("http_average_duration_ms", 50.0)
                    .containsEntry("telemetry_pending_count", 3L)
                    .containsEntry("telemetry_processing_lag_seconds", 42.5)
                    .containsEntry("telemetry_retrying_count", 2L)
                    .containsEntry("telemetry_dead_count", 1L);
        } finally {
            registry.close();
        }
    }

    private static void assertGauge(
            SimpleMeterRegistry registry, String meterName, double expected) {
        Gauge gauge = registry.find(meterName).gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(expected);
    }
}
