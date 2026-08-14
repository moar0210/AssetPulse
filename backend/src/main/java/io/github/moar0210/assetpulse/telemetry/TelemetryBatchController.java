package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/telemetry-batches")
public class TelemetryBatchController {

    private final TelemetryBatchService service;

    public TelemetryBatchController(TelemetryBatchService service) {
        this.service = service;
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TelemetryBatchResponse> accept(
            @Valid @RequestBody TelemetryBatchRequest request,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.accept(actor.organisationId(), request));
    }
}
