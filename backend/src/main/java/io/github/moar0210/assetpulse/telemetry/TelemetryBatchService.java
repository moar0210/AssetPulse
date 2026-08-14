package io.github.moar0210.assetpulse.telemetry;

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
    private final TelemetryBatchFingerprint fingerprint;

    public TelemetryBatchService(
            TelemetryBatchRepository repository, TelemetryBatchFingerprint fingerprint) {
        this.repository = repository;
        this.fingerprint = fingerprint;
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
            repository.insertReadings(organisationId, batch.id(), request.readings());
        }

        return new TelemetryBatchResponse(
                batch.id(), batch.idempotencyKey(), batch.readingCount(), batch.acceptedAt());
    }
}
