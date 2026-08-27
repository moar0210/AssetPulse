package io.github.moar0210.assetpulse.workorders;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkOrderListResponse> list(
            @RequestParam(required = false) String limit,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.list(actor, WorkOrderListRequest.fromQuery(limit)));
    }

    @GetMapping(path = "/eligible-technicians", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EligibleTechnicianListResponse> eligibleTechnicians(
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.eligibleTechnicians(actor.organisationId()));
    }

    @GetMapping(path = "/{workOrderId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkOrderResponse> detail(
            @PathVariable UUID workOrderId, @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.detail(actor, workOrderId));
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkOrderResponse> create(
            @Valid @RequestBody CreateWorkOrderRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        WorkOrderResponse created = service.create(actor.organisationId(), request.alertId());
        return ResponseEntity.created(URI.create("/api/v1/work-orders/" + created.id()))
                .cacheControl(CacheControl.noStore())
                .body(created);
    }

    @PostMapping(
            path = "/{workOrderId}/assign",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkOrderResponse> assign(
            @PathVariable UUID workOrderId,
            @Valid @RequestBody AssignWorkOrderRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.assign(actor.organisationId(), workOrderId, request));
    }
}
