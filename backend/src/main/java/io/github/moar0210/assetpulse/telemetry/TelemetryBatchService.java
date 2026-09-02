package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.observability.DurableTraceContext;
import io.github.moar0210.assetpulse.observability.TelemetryFlowTrace;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelemetryBatchService {

    private final TelemetryBatchRepository repository;
    private final TelemetryProcessingEventRepository processingEventRepository;
    private final TelemetryBatchFingerprint fingerprint;
    private final TelemetryFlowTrace flowTrace;

    public TelemetryBatchService(
            TelemetryBatchRepository repository,
            TelemetryProcessingEventRepository processingEventRepository,
            TelemetryBatchFingerprint fingerprint,
            TelemetryFlowTrace flowTrace) {
        this.repository = repository;
        this.processingEventRepository = processingEventRepository;
        this.fingerprint = fingerprint;
        this.flowTrace = flowTrace;
    }

    @Transactional
    public TelemetryBatchResponse accept(UUID organisationId, TelemetryBatchRequest request) {
        Set<UUID> requestedSensorIds =
                request.readings().stream()
                        .map(TelemetryBatchRequest.Reading::sensorId)
                        .collect(Collectors.toUnmodifiableSet());
        if (!repository
                .findOwnedSensorIds(organisationId, requestedSensorIds)
                .equals(requestedSensorIds)) {
            throw new InvalidSensorReferenceException();
        }

        String requestFingerprint = fingerprint.calculate(request);
        Optional<TelemetryBatchRepository.BatchRow> created =
                repository.tryCreate(
                        UUID.randomUUID(),
                        organisationId,
                        request.idempotencyKey(),
                        requestFingerprint,
                        request.readings().size(),
                        Instant.now());
        TelemetryBatchRepository.BatchRow batch =
                created.orElseGet(
                        () ->
                                repository.findByOrganisationIdAndIdempotencyKey(
                                        organisationId, request.idempotencyKey()));

        if (!batch.requestFingerprint().equals(requestFingerprint)) {
            throw new TelemetryIdempotencyConflictException();
        }

        if (created.isPresent()) {
            Span acceptanceSpan = flowTrace.startAcceptanceSpan();
            boolean completionDelegatedToTransaction = false;
            try (Tracer.SpanInScope ignored = flowTrace.activate(acceptanceSpan)) {
                repository.insertReadings(organisationId, batch.id(), request.readings());
                DurableTraceContext traceContext = flowTrace.captureCurrent();
                UUID flowId =
                        processingEventRepository.insertBatchAccepted(
                                organisationId,
                                batch.id(),
                                Instant.now(),
                                traceContext.traceParent(),
                                traceContext.traceState());
                flowTrace.acceptedAfterCommit(acceptanceSpan, flowId, organisationId, batch.id());
                completionDelegatedToTransaction = true;
            } catch (RuntimeException acceptanceFailure) {
                acceptanceSpan.error(
                        new IllegalStateException("Telemetry batch acceptance failed"));
                throw acceptanceFailure;
            } finally {
                if (!completionDelegatedToTransaction) {
                    acceptanceSpan.end();
                }
            }
        }

        return new TelemetryBatchResponse(
                batch.id(), batch.idempotencyKey(), batch.readingCount(), batch.acceptedAt());
    }
}
