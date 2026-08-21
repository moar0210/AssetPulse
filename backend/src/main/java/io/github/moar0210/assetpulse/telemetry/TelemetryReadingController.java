package io.github.moar0210.assetpulse.telemetry;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sensors/{sensorId}/telemetry-readings")
public class TelemetryReadingController {

    private final TelemetryReadingService service;

    public TelemetryReadingController(TelemetryReadingService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TelemetryReadingRangeResponse> readRange(
            @PathVariable UUID sensorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String limit,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        TelemetryReadingRangeRequest range =
                TelemetryReadingRangeRequest.fromQuery(from, to, limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.readRange(actor.organisationId(), sensorId, range));
    }
}
