package io.github.moar0210.assetpulse.alerts;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertCommandService {

    private final AlertCommandRepository commandRepository;
    private final AlertQueryRepository queryRepository;
    private final AlertChangePublisher changePublisher;

    public AlertCommandService(
            AlertCommandRepository commandRepository,
            AlertQueryRepository queryRepository,
            AlertChangePublisher changePublisher) {
        this.commandRepository = commandRepository;
        this.queryRepository = queryRepository;
        this.changePublisher = changePublisher;
    }

    @Transactional
    public AlertDetailResponse acknowledge(UUID organisationId, UUID actorUserId, UUID alertId) {
        return transition(
                organisationId,
                actorUserId,
                alertId,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                1);
    }

    @Transactional
    public AlertDetailResponse resolve(UUID organisationId, UUID actorUserId, UUID alertId) {
        return transition(
                organisationId,
                actorUserId,
                alertId,
                AlertStatus.ACKNOWLEDGED,
                AlertStatus.RESOLVED,
                2);
    }

    private AlertDetailResponse transition(
            UUID organisationId,
            UUID actorUserId,
            UUID alertId,
            AlertStatus expectedStatus,
            AlertStatus targetStatus,
            int sequenceNumber) {
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
