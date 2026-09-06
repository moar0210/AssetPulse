package io.github.moar0210.assetpulse.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.moar0210.assetpulse.alerts.AlertCommandService;
import io.github.moar0210.assetpulse.alerts.AlertDetailResponse;
import io.github.moar0210.assetpulse.alerts.AlertStatus;
import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.demo.DemoResetRepository.ActiveAlert;
import io.github.moar0210.assetpulse.demo.DemoResetRepository.ActiveWorkOrder;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.workorders.AssignWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.TransitionWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.WorkOrderDetailResponse;
import io.github.moar0210.assetpulse.workorders.WorkOrderService;
import io.github.moar0210.assetpulse.workorders.WorkOrderStateConflictException;
import io.github.moar0210.assetpulse.workorders.WorkOrderStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class DemoResetServiceTest {

    private static final UUID ORGANISATION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID OPEN_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID ACKNOWLEDGED_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000002");
    private static final UUID OPEN_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID ASSIGNED_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID IN_PROGRESS_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000003");
    private static final String CORRELATION_ID = "c1000000-0000-0000-0000-000000000001";

    private DemoResetRepository repository;
    private AlertCommandService alertCommandService;
    private WorkOrderService workOrderService;
    private AuditService auditService;
    private DemoResetService service;

    @BeforeEach
    void setUp() {
        repository = mock(DemoResetRepository.class);
        alertCommandService = mock(AlertCommandService.class);
        workOrderService = mock(WorkOrderService.class);
        auditService = mock(AuditService.class);
        service =
                new DemoResetService(
                        repository, alertCommandService, workOrderService, auditService);
    }

    @Test
    void completesEveryActiveLifecycleAndRecordsOneResetSummary() {
        AuthenticatedActor administrator = actor(ADMIN_ID, "OPERATIONS_ADMIN");
        AuthenticatedActor technician = actor(TECHNICIAN_ID, "TECHNICIAN");
        when(repository.findActiveAlertsForUpdate(ORGANISATION_ID, 101))
                .thenReturn(
                        List.of(
                                new ActiveAlert(OPEN_ALERT_ID, AlertStatus.OPEN),
                                new ActiveAlert(ACKNOWLEDGED_ALERT_ID, AlertStatus.ACKNOWLEDGED)));
        when(repository.findActiveWorkOrdersForUpdate(ORGANISATION_ID, 101))
                .thenReturn(
                        List.of(
                                new ActiveWorkOrder(
                                        OPEN_WORK_ORDER_ID, WorkOrderStatus.OPEN, 1, null),
                                new ActiveWorkOrder(
                                        ASSIGNED_WORK_ORDER_ID,
                                        WorkOrderStatus.ASSIGNED,
                                        5,
                                        TECHNICIAN_ID),
                                new ActiveWorkOrder(
                                        IN_PROGRESS_WORK_ORDER_ID,
                                        WorkOrderStatus.IN_PROGRESS,
                                        9,
                                        TECHNICIAN_ID)));
        when(repository.findFirstTechnician(ORGANISATION_ID)).thenReturn(Optional.of(technician));
        when(repository.findTechnician(ORGANISATION_ID, TECHNICIAN_ID))
                .thenReturn(Optional.of(technician));
        when(workOrderService.assign(
                        ORGANISATION_ID,
                        ADMIN_ID,
                        OPEN_WORK_ORDER_ID,
                        new AssignWorkOrderRequest(TECHNICIAN_ID, 1L),
                        CORRELATION_ID))
                .thenReturn(workOrder(OPEN_WORK_ORDER_ID, WorkOrderStatus.ASSIGNED, 2));
        when(workOrderService.start(
                        technician,
                        OPEN_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(2L),
                        CORRELATION_ID))
                .thenReturn(workOrder(OPEN_WORK_ORDER_ID, WorkOrderStatus.IN_PROGRESS, 3));
        when(workOrderService.start(
                        technician,
                        ASSIGNED_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(5L),
                        CORRELATION_ID))
                .thenReturn(workOrder(ASSIGNED_WORK_ORDER_ID, WorkOrderStatus.IN_PROGRESS, 6));
        when(alertCommandService.acknowledge(
                        ORGANISATION_ID, ADMIN_ID, OPEN_ALERT_ID, CORRELATION_ID))
                .thenReturn(alert(OPEN_ALERT_ID, AlertStatus.ACKNOWLEDGED));

        Instant before = Instant.now();
        DemoResetResponse response = service.reset(administrator, CORRELATION_ID);

        assertThat(response.alertsResolved()).isEqualTo(2);
        assertThat(response.workOrdersCompleted()).isEqualTo(3);
        assertThat(response.resetAt()).isBetween(before, Instant.now());
        verify(workOrderService)
                .complete(
                        technician,
                        OPEN_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(3L),
                        CORRELATION_ID);
        verify(workOrderService)
                .complete(
                        technician,
                        ASSIGNED_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(6L),
                        CORRELATION_ID);
        verify(workOrderService)
                .complete(
                        technician,
                        IN_PROGRESS_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(9L),
                        CORRELATION_ID);
        verify(alertCommandService)
                .resolve(ORGANISATION_ID, ADMIN_ID, OPEN_ALERT_ID, CORRELATION_ID);
        verify(alertCommandService)
                .resolve(ORGANISATION_ID, ADMIN_ID, ACKNOWLEDGED_ALERT_ID, CORRELATION_ID);
        verify(auditService)
                .record(
                        ORGANISATION_ID,
                        ADMIN_ID,
                        AuditAction.DEMO_RESET,
                        ADMIN_ID,
                        CORRELATION_ID);
    }

    @Test
    void rejectsNonAdministratorsBeforeReadingOrganisationState() {
        assertThatThrownBy(() -> service.reset(actor(TECHNICIAN_ID, "TECHNICIAN"), CORRELATION_ID))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository, alertCommandService, workOrderService, auditService);
    }

    @Test
    void refusesAnUnboundedResetBeforeApplyingAnyMutation() {
        when(repository.findActiveAlertsForUpdate(ORGANISATION_ID, 101))
                .thenReturn(
                        Collections.nCopies(101, new ActiveAlert(OPEN_ALERT_ID, AlertStatus.OPEN)));
        when(repository.findActiveWorkOrdersForUpdate(ORGANISATION_ID, 101)).thenReturn(List.of());

        assertThatThrownBy(() -> service.reset(actor(ADMIN_ID, "OPERATIONS_ADMIN"), CORRELATION_ID))
                .isInstanceOf(DemoResetLimitExceededException.class);
        verifyNoInteractions(alertCommandService, workOrderService, auditService);
    }

    @Test
    void convertsLifecycleConflictsToTheGenericUnavailableBoundary() {
        AuthenticatedActor technician = actor(TECHNICIAN_ID, "TECHNICIAN");
        when(repository.findActiveAlertsForUpdate(ORGANISATION_ID, 101)).thenReturn(List.of());
        when(repository.findActiveWorkOrdersForUpdate(ORGANISATION_ID, 101))
                .thenReturn(
                        List.of(
                                new ActiveWorkOrder(
                                        IN_PROGRESS_WORK_ORDER_ID,
                                        WorkOrderStatus.IN_PROGRESS,
                                        9,
                                        TECHNICIAN_ID)));
        when(repository.findTechnician(ORGANISATION_ID, TECHNICIAN_ID))
                .thenReturn(Optional.of(technician));
        when(workOrderService.complete(
                        technician,
                        IN_PROGRESS_WORK_ORDER_ID,
                        new TransitionWorkOrderRequest(9L),
                        CORRELATION_ID))
                .thenThrow(new WorkOrderStateConflictException());

        assertThatThrownBy(() -> service.reset(actor(ADMIN_ID, "OPERATIONS_ADMIN"), CORRELATION_ID))
                .isInstanceOf(DemoResetUnavailableException.class)
                .hasCauseInstanceOf(WorkOrderStateConflictException.class);
        verifyNoInteractions(auditService);
    }

    private AuthenticatedActor actor(UUID userId, String roleCode) {
        return new AuthenticatedActor(
                userId,
                roleCode.toLowerCase() + "@example.test",
                roleCode,
                "unused-password",
                ORGANISATION_ID,
                "test-organisation",
                "Test Organisation",
                roleCode,
                roleCode);
    }

    private WorkOrderDetailResponse workOrder(UUID id, WorkOrderStatus status, long version) {
        return new WorkOrderDetailResponse(
                id, null, status, version, null, Instant.EPOCH, Instant.EPOCH, null, List.of());
    }

    private AlertDetailResponse alert(UUID id, AlertStatus status) {
        return new AlertDetailResponse(
                id,
                status,
                1,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                List.of());
    }
}
