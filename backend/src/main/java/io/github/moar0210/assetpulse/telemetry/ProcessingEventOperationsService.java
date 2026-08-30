package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessingEventOperationsService {

    private final ProcessingEventOperationsRepository repository;
    private final AuditService auditService;

    public ProcessingEventOperationsService(
            ProcessingEventOperationsRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public DeadProcessingEventListResponse listDeadForOrganisation(
            UUID organisationId, DeadProcessingEventListRequest request) {
        return new DeadProcessingEventListResponse(
                repository.findDeadByOrganisationId(organisationId, request.limit()),
                request.limit());
    }

    @Transactional
    public void retryDeadForOrganisation(
            UUID organisationId, UUID actorUserId, UUID eventId, String correlationId) {
        int updated = repository.retryDeadForOrganisation(organisationId, eventId, Instant.now());
        if (updated == 1) {
            auditService.record(
                    organisationId,
                    actorUserId,
                    AuditAction.PROCESSING_EVENT_RETRIED,
                    eventId,
                    correlationId);
            return;
        }
        if (repository.existsByOrganisationIdAndId(organisationId, eventId)) {
            throw new ProcessingEventStateConflictException();
        }
        throw new ProcessingEventNotFoundException();
    }
}
