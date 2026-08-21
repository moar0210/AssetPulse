package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
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
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertQueryService queryService;
    private final AlertCommandService commandService;

    public AlertController(AlertQueryService queryService, AlertCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertListResponse> list(
            @RequestParam(required = false) String limit,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        AlertListRequest request = AlertListRequest.fromQuery(limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queryService.listForOrganisation(actor.organisationId(), request));
    }

    @GetMapping(path = "/{alertId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertDetailResponse> detail(
            @PathVariable UUID alertId, @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queryService.detailForOrganisation(actor.organisationId(), alertId));
    }

    @PostMapping(path = "/{alertId}/acknowledge", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertDetailResponse> acknowledge(
            @PathVariable UUID alertId, @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(commandService.acknowledge(actor.organisationId(), actor.userId(), alertId));
    }

    @PostMapping(path = "/{alertId}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertDetailResponse> resolve(
            @PathVariable UUID alertId, @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(commandService.resolve(actor.organisationId(), actor.userId(), alertId));
    }
}
