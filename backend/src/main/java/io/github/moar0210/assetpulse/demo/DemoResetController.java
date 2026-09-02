package io.github.moar0210.assetpulse.demo;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoResetController {

    private final DemoResetService service;

    public DemoResetController(DemoResetService service) {
        this.service = service;
    }

    @PostMapping(path = "/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DemoResetResponse> reset(
            @AuthenticationPrincipal AuthenticatedActor actor, HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.reset(actor, CorrelationIdFilter.from(request)));
        } catch (DataAccessException | TransactionException exception) {
            throw new DemoResetUnavailableException(exception);
        }
    }
}
