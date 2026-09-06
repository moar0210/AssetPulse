package io.github.moar0210.assetpulse.demo;

import io.github.moar0210.assetpulse.alerts.AlertCommandService;
import io.github.moar0210.assetpulse.alerts.AlertNotFoundException;
import io.github.moar0210.assetpulse.alerts.AlertStateConflictException;
import io.github.moar0210.assetpulse.alerts.AlertStatus;
import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.demo.DemoResetRepository.ActiveAlert;
import io.github.moar0210.assetpulse.demo.DemoResetRepository.ActiveWorkOrder;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.workorders.AssignWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.InvalidWorkOrderAssigneeException;
import io.github.moar0210.assetpulse.workorders.TransitionWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.WorkOrderDetailResponse;
import io.github.moar0210.assetpulse.workorders.WorkOrderNotFoundException;
import io.github.moar0210.assetpulse.workorders.WorkOrderService;
import io.github.moar0210.assetpulse.workorders.WorkOrderStateConflictException;
import io.github.moar0210.assetpulse.workorders.WorkOrderStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoResetService {

    static final int MAX_ACTIVE_ALERTS = 100;
    static final int MAX_ACTIVE_WORK_ORDERS = 100;
    private static final int BOUNDED_QUERY_LIMIT = 101;

    private final DemoResetRepository repository;
    private final AlertCommandService alertCommandService;
    private final WorkOrderService workOrderService;
    private final AuditService auditService;

    public DemoResetService(
            DemoResetRepository repository,
            AlertCommandService alertCommandService,
            WorkOrderService workOrderService,
            AuditService auditService) {
        this.repository = repository;
        this.alertCommandService = alertCommandService;
        this.workOrderService = workOrderService;
        this.auditService = auditService;
    }

    @Transactional
    public DemoResetResponse reset(AuthenticatedActor administrator, String correlationId) {
        if (!"OPERATIONS_ADMIN".equals(administrator.roleCode())) {
            throw new AccessDeniedException(
                    "Only operations administrators can reset the demo state");
        }
        try {
            UUID organisationId = administrator.organisationId();
            List<ActiveAlert> activeAlerts =
                    repository.findActiveAlertsForUpdate(organisationId, BOUNDED_QUERY_LIMIT);
            List<ActiveWorkOrder> activeWorkOrders =
                    repository.findActiveWorkOrdersForUpdate(organisationId, BOUNDED_QUERY_LIMIT);

            enforceBounds(activeAlerts, activeWorkOrders);
            Map<UUID, AuthenticatedActor> workOrderTechnicians =
                    resolveWorkOrderTechnicians(organisationId, activeWorkOrders);

            for (ActiveWorkOrder workOrder : activeWorkOrders) {
                completeWorkOrder(
                        administrator,
                        workOrder,
                        workOrderTechnicians.get(workOrder.id()),
                        correlationId);
            }
            for (ActiveAlert alert : activeAlerts) {
                resolveAlert(administrator, alert, correlationId);
            }

            auditService.record(
                    organisationId,
                    administrator.userId(),
                    AuditAction.DEMO_RESET,
                    administrator.userId(),
                    correlationId);
            return new DemoResetResponse(
                    Instant.now(), activeAlerts.size(), activeWorkOrders.size());
        } catch (DemoResetLimitExceededException | DemoResetUnavailableException exception) {
            throw exception;
        } catch (AlertNotFoundException
                | AlertStateConflictException
                | WorkOrderNotFoundException
                | WorkOrderStateConflictException
                | InvalidWorkOrderAssigneeException
                | DataAccessException
                | TransactionException
                | IllegalArgumentException
                | IllegalStateException exception) {
            throw new DemoResetUnavailableException(exception);
        }
    }

    private static void enforceBounds(
            List<ActiveAlert> activeAlerts, List<ActiveWorkOrder> activeWorkOrders) {
        if (activeAlerts.size() > MAX_ACTIVE_ALERTS
                || activeWorkOrders.size() > MAX_ACTIVE_WORK_ORDERS) {
            throw new DemoResetLimitExceededException();
        }
    }

    private Map<UUID, AuthenticatedActor> resolveWorkOrderTechnicians(
            UUID organisationId, List<ActiveWorkOrder> activeWorkOrders) {
        Map<UUID, AuthenticatedActor> technicians = new LinkedHashMap<>();
        AuthenticatedActor defaultTechnician = null;

        for (ActiveWorkOrder workOrder : activeWorkOrders) {
            AuthenticatedActor technician;
            if (workOrder.status() == WorkOrderStatus.OPEN) {
                if (defaultTechnician == null) {
                    defaultTechnician =
                            repository
                                    .findFirstTechnician(organisationId)
                                    .orElseThrow(DemoResetUnavailableException::new);
                }
                technician = defaultTechnician;
            } else {
                technician =
                        repository
                                .findTechnician(organisationId, workOrder.technicianUserId())
                                .orElseThrow(DemoResetUnavailableException::new);
            }
            technicians.put(workOrder.id(), technician);
        }
        return technicians;
    }

    private void completeWorkOrder(
            AuthenticatedActor administrator,
            ActiveWorkOrder workOrder,
            AuthenticatedActor technician,
            String correlationId) {
        WorkOrderStatus status = workOrder.status();
        long version = workOrder.version();

        if (status == WorkOrderStatus.OPEN) {
            WorkOrderDetailResponse assigned =
                    workOrderService.assign(
                            administrator.organisationId(),
                            administrator.userId(),
                            workOrder.id(),
                            new AssignWorkOrderRequest(technician.userId(), version),
                            correlationId);
            status = assigned.status();
            version = assigned.version();
        }

        if (status == WorkOrderStatus.ASSIGNED) {
            WorkOrderDetailResponse started =
                    workOrderService.start(
                            technician,
                            workOrder.id(),
                            new TransitionWorkOrderRequest(version),
                            correlationId);
            status = started.status();
            version = started.version();
        }

        if (status != WorkOrderStatus.IN_PROGRESS) {
            throw new DemoResetUnavailableException();
        }
        workOrderService.complete(
                technician, workOrder.id(), new TransitionWorkOrderRequest(version), correlationId);
    }

    private void resolveAlert(
            AuthenticatedActor administrator, ActiveAlert alert, String correlationId) {
        AlertStatus status = alert.status();
        if (status == AlertStatus.OPEN) {
            status =
                    alertCommandService
                            .acknowledge(
                                    administrator.organisationId(),
                                    administrator.userId(),
                                    alert.id(),
                                    correlationId)
                            .status();
        }
        if (status != AlertStatus.ACKNOWLEDGED) {
            throw new DemoResetUnavailableException();
        }
        alertCommandService.resolve(
                administrator.organisationId(), administrator.userId(), alert.id(), correlationId);
    }
}
