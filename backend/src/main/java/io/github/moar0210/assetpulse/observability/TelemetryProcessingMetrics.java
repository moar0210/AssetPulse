package io.github.moar0210.assetpulse.observability;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingMetricsSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "assetpulse.observability.metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TelemetryProcessingMetrics {

    private final AtomicLong pending = new AtomicLong();
    private final AtomicReference<Double> lagSeconds = new AtomicReference<>(0.0);
    private final AtomicLong retrying = new AtomicLong();
    private final AtomicLong dead = new AtomicLong();

    public TelemetryProcessingMetrics(MeterRegistry registry) {
        Gauge.builder("assetpulse.telemetry.processing.pending", pending, AtomicLong::doubleValue)
                .description("Telemetry processing events waiting for work")
                .baseUnit("events")
                .register(registry);
        Gauge.builder(
                        "assetpulse.telemetry.processing.lag.seconds",
                        lagSeconds,
                        value -> value.get())
                .description("Age of the oldest pending or processing telemetry event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("assetpulse.telemetry.processing.retrying", retrying, AtomicLong::doubleValue)
                .description("Pending telemetry events that have already failed an attempt")
                .baseUnit("events")
                .register(registry);
        Gauge.builder("assetpulse.telemetry.processing.dead", dead, AtomicLong::doubleValue)
                .description("Telemetry processing events in the dead state")
                .baseUnit("events")
                .register(registry);
    }

    public void update(TelemetryProcessingMetricsSnapshot snapshot) {
        pending.set(snapshot.pendingCount());
        lagSeconds.set(snapshot.processingLagSeconds());
        retrying.set(snapshot.retryingCount());
        dead.set(snapshot.deadCount());
    }
}
