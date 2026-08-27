package io.github.moar0210.assetpulse.telemetry;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingEventOperationsService {

    private final ProcessingEventOperationsRepository repository;

    public ProcessingEventOperationsService(ProcessingEventOperationsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public DeadProcessingEventListResponse listDeadForOrganisation(
            UUID organisationId, DeadProcessingEventListRequest request) {
        return new DeadProcessingEventListResponse(
                repository.findDeadByOrganisationId(organisationId, request.limit()),
                request.limit());
    }

    @Transactional
    public void retryDeadForOrganisation(UUID organisationId, UUID eventId) {
        int updated = repository.retryDeadForOrganisation(organisationId, eventId, Instant.now());
        if (updated == 1) {
            return;
        }
        if (repository.existsByOrganisationIdAndId(organisationId, eventId)) {
            throw new ProcessingEventStateConflictException();
        }
        throw new ProcessingEventNotFoundException();
    }
}
