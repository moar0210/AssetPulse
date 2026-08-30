package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/processing-events")
public class ProcessingEventOperationsController {

    private final ProcessingEventOperationsService operationsService;

    public ProcessingEventOperationsController(ProcessingEventOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping(path = "/dead", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DeadProcessingEventListResponse> listDead(
            @RequestParam(required = false) String limit,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        DeadProcessingEventListRequest request = DeadProcessingEventListRequest.fromQuery(limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(operationsService.listDeadForOrganisation(actor.organisationId(), request));
    }

    @PostMapping("/{eventId}/retry")
    public ResponseEntity<Void> retry(
            @PathVariable UUID eventId,
            @AuthenticationPrincipal AuthenticatedActor actor,
            HttpServletRequest request) {
        operationsService.retryDeadForOrganisation(
                actor.organisationId(), actor.userId(), eventId, CorrelationIdFilter.from(request));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
