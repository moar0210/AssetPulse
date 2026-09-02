package io.github.moar0210.assetpulse.observability;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingMetricsRepository;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingMetricsSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "assetpulse.observability.metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OperationalMetricsReporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalMetricsReporter.class);

    private final TelemetryProcessingMetricsRepository repository;
    private final TelemetryProcessingMetrics processingMetrics;
    private final MeterRegistry meterRegistry;

    public OperationalMetricsReporter(
            TelemetryProcessingMetricsRepository repository,
            TelemetryProcessingMetrics processingMetrics,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.processingMetrics = processingMetrics;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(
            initialDelayString = "${assetpulse.observability.metrics.initial-delay-millis:15000}",
            fixedDelayString = "${assetpulse.observability.metrics.report-delay-millis:60000}")
    public void report() {
        Instant observedAt = Instant.now();
        try {
            TelemetryProcessingMetricsSnapshot snapshot = repository.readSnapshot(observedAt);
            processingMetrics.update(snapshot);
            HttpMetrics http = readHttpMetrics();
            LOGGER.atInfo()
                    .addKeyValue("event", "operational_metrics")
                    .addKeyValue("observed_at", snapshot.observedAt())
                    .addKeyValue("http_request_count", http.requestCount())
                    .addKeyValue("http_error_count", http.errorCount())
                    .addKeyValue("http_average_duration_ms", http.averageDurationMillis())
                    .addKeyValue("http_max_duration_ms", http.maxDurationMillis())
                    .addKeyValue("telemetry_pending_count", snapshot.pendingCount())
                    .addKeyValue(
                            "telemetry_processing_lag_seconds", snapshot.processingLagSeconds())
                    .addKeyValue("telemetry_retrying_count", snapshot.retryingCount())
                    .addKeyValue("telemetry_dead_count", snapshot.deadCount())
                    .log("Operational metrics snapshot");
        } catch (DataAccessException exception) {
            LOGGER.atWarn()
                    .addKeyValue("event", "operational_metrics_unavailable")
                    .log("Operational metrics snapshot could not be read");
        }
    }

    private HttpMetrics readHttpMetrics() {
        Collection<Timer> timers = meterRegistry.find("http.server.requests").timers();
        long count = timers.stream().mapToLong(Timer::count).sum();
        long errors =
                timers.stream()
                        .filter(OperationalMetricsReporter::isError)
                        .mapToLong(Timer::count)
                        .sum();
        double totalMillis =
                timers.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum();
        double maxMillis =
                timers.stream()
                        .mapToDouble(timer -> timer.max(TimeUnit.MILLISECONDS))
                        .max()
                        .orElse(0.0);
        return new HttpMetrics(count, errors, count == 0 ? 0.0 : totalMillis / count, maxMillis);
    }

    private static boolean isError(Timer timer) {
        String status = timer.getId().getTag("status");
        return status != null && (status.startsWith("4") || status.startsWith("5"));
    }

    private record HttpMetrics(
            long requestCount,
            long errorCount,
            double averageDurationMillis,
            double maxDurationMillis) {}
}
