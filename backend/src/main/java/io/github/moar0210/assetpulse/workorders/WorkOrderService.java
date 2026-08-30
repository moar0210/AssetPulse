package io.github.moar0210.assetpulse.workorders;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkOrderService {

    private static final String TECHNICIAN_ROLE = "TECHNICIAN";

    private final WorkOrderRepository repository;
    private final AuditService auditService;

    public WorkOrderService(WorkOrderRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WorkOrderDetailResponse detail(AuthenticatedActor actor, UUID workOrderId) {
        WorkOrderResponse summary;
        if (isTechnician(actor)) {
            summary =
                    repository
                            .findAssignedByOrganisationIdAndIdAndTechnicianId(
                                    actor.organisationId(), workOrderId, actor.userId())
                            .orElseThrow(WorkOrderNotFoundException::new);
        } else {
            summary =
                    repository
                            .findByOrganisationIdAndId(actor.organisationId(), workOrderId)
                            .orElseThrow(WorkOrderNotFoundException::new);
        }
        return WorkOrderDetailResponse.from(
                summary, repository.findHistory(actor.organisationId(), workOrderId));
    }

    @Transactional(readOnly = true)
    public EligibleTechnicianListResponse eligibleTechnicians(UUID organisationId) {
        return new EligibleTechnicianListResponse(
                repository.findEligibleTechnicians(organisationId));
    }

    @Transactional
    public WorkOrderDetailResponse create(
            UUID organisationId, UUID actorUserId, UUID alertId, String correlationId) {
        UUID workOrderId = UUID.randomUUID();
        int inserted =
                repository.insertFromAlert(workOrderId, organisationId, alertId, Instant.now());
        if (inserted == 0) {
            if (repository.sourceAlertExists(organisationId, alertId)) {
                throw new WorkOrderAlreadyExistsException();
            }
            throw new WorkOrderSourceAlertNotFoundException();
        }
        auditService.record(
                organisationId,
                actorUserId,
                AuditAction.WORK_ORDER_CREATED,
                workOrderId,
                correlationId);
        return commandDetail(organisationId, workOrderId);
    }

    @Transactional
    public WorkOrderDetailResponse assign(
            UUID organisationId,
            UUID actorUserId,
            UUID workOrderId,
            AssignWorkOrderRequest request,
            String correlationId) {
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
        repository.insertHistory(organisationId, workOrderId, WorkOrderStatus.OPEN, actorUserId);
        auditService.record(
                organisationId,
                actorUserId,
                AuditAction.WORK_ORDER_ASSIGNED,
                workOrderId,
                correlationId);
        return commandDetail(organisationId, workOrderId);
    }

    @Transactional
    public WorkOrderDetailResponse start(
            AuthenticatedActor actor,
            UUID workOrderId,
            TransitionWorkOrderRequest request,
            String correlationId) {
        return transition(
                actor,
                workOrderId,
                request.expectedVersion(),
                WorkOrderStatus.ASSIGNED,
                WorkOrderStatus.IN_PROGRESS,
                AuditAction.WORK_ORDER_STARTED,
                correlationId);
    }

    @Transactional
    public WorkOrderDetailResponse complete(
            AuthenticatedActor actor,
            UUID workOrderId,
            TransitionWorkOrderRequest request,
            String correlationId) {
        return transition(
                actor,
                workOrderId,
                request.expectedVersion(),
                WorkOrderStatus.IN_PROGRESS,
                WorkOrderStatus.DONE,
                AuditAction.WORK_ORDER_COMPLETED,
                correlationId);
    }

    private WorkOrderDetailResponse transition(
            AuthenticatedActor actor,
            UUID workOrderId,
            long expectedVersion,
            WorkOrderStatus expectedStatus,
            WorkOrderStatus targetStatus,
            AuditAction auditAction,
            String correlationId) {
        if (!isTechnician(actor)) {
            throw new AccessDeniedException("Only technicians can transition assigned work");
        }

        int updated =
                repository.transition(
                        actor.organisationId(),
                        workOrderId,
                        actor.userId(),
                        expectedVersion,
                        expectedStatus,
                        targetStatus,
                        Instant.now());
        if (updated == 0) {
            if (repository
                    .findAssignedByOrganisationIdAndIdAndTechnicianId(
                            actor.organisationId(), workOrderId, actor.userId())
                    .isPresent()) {
                throw new WorkOrderStateConflictException();
            }
            throw new WorkOrderNotFoundException();
        }

        repository.insertHistory(
                actor.organisationId(), workOrderId, expectedStatus, actor.userId());
        auditService.record(
                actor.organisationId(), actor.userId(), auditAction, workOrderId, correlationId);
        return commandDetail(actor.organisationId(), workOrderId);
    }

    private WorkOrderDetailResponse commandDetail(UUID organisationId, UUID workOrderId) {
        WorkOrderResponse summary =
                repository
                        .findByOrganisationIdAndId(organisationId, workOrderId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Changed work order could not be read"));
        return WorkOrderDetailResponse.from(
                summary, repository.findHistory(organisationId, workOrderId));
    }

    private static boolean isTechnician(AuthenticatedActor actor) {
        return TECHNICIAN_ROLE.equals(actor.roleCode());
    }
}
