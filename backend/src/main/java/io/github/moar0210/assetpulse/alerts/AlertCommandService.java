package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertCommandService {

    private final AlertCommandRepository commandRepository;
    private final AlertQueryRepository queryRepository;
    private final AlertChangePublisher changePublisher;
    private final AuditService auditService;

    public AlertCommandService(
            AlertCommandRepository commandRepository,
            AlertQueryRepository queryRepository,
            AlertChangePublisher changePublisher,
            AuditService auditService) {
        this.commandRepository = commandRepository;
        this.queryRepository = queryRepository;
        this.changePublisher = changePublisher;
        this.auditService = auditService;
    }

    @Transactional
    public AlertDetailResponse acknowledge(
            UUID organisationId, UUID actorUserId, UUID alertId, String correlationId) {
        return transition(
                organisationId,
                actorUserId,
                alertId,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                1,
                AuditAction.ALERT_ACKNOWLEDGED,
                correlationId);
    }

    @Transactional
    public AlertDetailResponse resolve(
            UUID organisationId, UUID actorUserId, UUID alertId, String correlationId) {
        return transition(
                organisationId,
                actorUserId,
                alertId,
                AlertStatus.ACKNOWLEDGED,
                AlertStatus.RESOLVED,
                2,
                AuditAction.ALERT_RESOLVED,
                correlationId);
    }

    private AlertDetailResponse transition(
            UUID organisationId,
            UUID actorUserId,
            UUID alertId,
            AlertStatus expectedStatus,
            AlertStatus targetStatus,
            int sequenceNumber,
            AuditAction auditAction,
            String correlationId) {
        if (!expectedStatus.canTransitionTo(targetStatus)) {
            throw new IllegalArgumentException("Unsupported alert status transition");
        }

        Instant transitionedAt = Instant.now();
        int updated =
                commandRepository.transition(
                        organisationId, alertId, expectedStatus, targetStatus, transitionedAt);
        if (updated == 0) {
            if (commandRepository
                    .findStatusByOrganisationIdAndId(organisationId, alertId)
                    .isPresent()) {
                throw new AlertStateConflictException();
            }
            throw new AlertNotFoundException();
        }

        commandRepository.insertHistory(
                organisationId,
                alertId,
                sequenceNumber,
                expectedStatus,
                targetStatus,
                actorUserId,
                transitionedAt);
        auditService.record(organisationId, actorUserId, auditAction, alertId, correlationId);
        AlertDetailResponse response =
                queryRepository
                        .findByOrganisationIdAndId(organisationId, alertId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Transitioned alert could not be read"));
        changePublisher.publishAfterCommit(organisationId, alertId, AlertChangeType.STATUS_CHANGED);
        return response;
    }
}
