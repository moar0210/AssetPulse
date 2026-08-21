package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEvent;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingEventHandler;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ThresholdAlertHandler implements TelemetryProcessingEventHandler {

    private static final String TELEMETRY_BATCH_ACCEPTED = "TELEMETRY_BATCH_ACCEPTED";

    private final ThresholdAlertRepository repository;
    private final ThresholdRuleEvaluator evaluator;
    private final AlertFingerprint fingerprint;
    private final AlertCooldownPolicy cooldownPolicy;

    public ThresholdAlertHandler(
            ThresholdAlertRepository repository,
            ThresholdRuleEvaluator evaluator,
            AlertFingerprint fingerprint,
            AlertCooldownPolicy cooldownPolicy) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.fingerprint = fingerprint;
        this.cooldownPolicy = cooldownPolicy;
    }

    @Override
    public void handle(TelemetryProcessingEvent event) {
        if (!TELEMETRY_BATCH_ACCEPTED.equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported telemetry processing event type");
        }

        repository.findEvaluations(event.organisationId(), event.telemetryBatchId()).stream()
                .filter(
                        evaluation ->
                                evaluator.isBreached(
                                        evaluation.value(),
                                        evaluation.comparison(),
                                        evaluation.thresholdValue()))
                .forEach(
                        evaluation ->
                                repository.recordOccurrence(
                                        UUID.randomUUID(),
                                        event.organisationId(),
                                        evaluation.thresholdRuleId(),
                                        fingerprint.calculate(
                                                event.organisationId(),
                                                evaluation.thresholdRuleId()),
                                        evaluation.observedAt(),
                                        cooldownPolicy.deadline(
                                                evaluation.observedAt(),
                                                evaluation.cooldownSeconds()),
                                        event.createdAt()));
    }
}
