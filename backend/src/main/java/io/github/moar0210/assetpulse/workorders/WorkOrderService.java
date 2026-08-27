package io.github.moar0210.assetpulse.workorders;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {

    private static final String TECHNICIAN_ROLE = "TECHNICIAN";

    private final WorkOrderRepository repository;

    public WorkOrderService(WorkOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public WorkOrderListResponse list(AuthenticatedActor actor, WorkOrderListRequest request) {
        if (isTechnician(actor)) {
            return new WorkOrderListResponse(
                    repository.findAssignedByOrganisationIdAndTechnicianId(
                            actor.organisationId(), actor.userId(), request.limit()),
                    request.limit());
        }
        return new WorkOrderListResponse(
                repository.findByOrganisationId(actor.organisationId(), request.limit()),
                request.limit());
    }

    @Transactional(readOnly = true)
    public WorkOrderResponse detail(AuthenticatedActor actor, UUID workOrderId) {
        if (isTechnician(actor)) {
            return repository
                    .findAssignedByOrganisationIdAndIdAndTechnicianId(
                            actor.organisationId(), workOrderId, actor.userId())
                    .orElseThrow(WorkOrderNotFoundException::new);
        }
        return repository
                .findByOrganisationIdAndId(actor.organisationId(), workOrderId)
                .orElseThrow(WorkOrderNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public EligibleTechnicianListResponse eligibleTechnicians(UUID organisationId) {
        return new EligibleTechnicianListResponse(
                repository.findEligibleTechnicians(organisationId));
    }

    @Transactional
    public WorkOrderResponse create(UUID organisationId, UUID alertId) {
        UUID workOrderId = UUID.randomUUID();
        int inserted =
                repository.insertFromAlert(workOrderId, organisationId, alertId, Instant.now());
        if (inserted == 0) {
            if (repository.sourceAlertExists(organisationId, alertId)) {
                throw new WorkOrderAlreadyExistsException();
            }
            throw new WorkOrderSourceAlertNotFoundException();
        }
        return repository
                .findByOrganisationIdAndId(organisationId, workOrderId)
                .orElseThrow(
                        () -> new IllegalStateException("Created work order could not be read"));
    }

    @Transactional
    public WorkOrderResponse assign(
            UUID organisationId, UUID workOrderId, AssignWorkOrderRequest request) {
        if (!repository.workOrderExists(organisationId, workOrderId)) {
            throw new WorkOrderNotFoundException();
        }
        UUID technicianUserId = request.technicianUserId();
        if (technicianUserId == null
                || !repository.eligibleTechnicianExists(organisationId, technicianUserId)) {
            throw new InvalidWorkOrderAssigneeException();
        }

        int updated =
                repository.assign(
                        organisationId,
                        workOrderId,
                        technicianUserId,
                        request.expectedVersion(),
                        Instant.now());
        if (updated == 0) {
            throw new WorkOrderStateConflictException();
        }
        return repository
                .findByOrganisationIdAndId(organisationId, workOrderId)
                .orElseThrow(
                        () -> new IllegalStateException("Assigned work order could not be read"));
    }

    private static boolean isTechnician(AuthenticatedActor actor) {
        return TECHNICIAN_ROLE.equals(actor.roleCode());
    }
}
