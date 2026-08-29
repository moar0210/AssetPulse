package io.github.moar0210.assetpulse.audit;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuditListResponse> list(
            @RequestParam(required = false) String limit,
            @AuthenticationPrincipal AuthenticatedActor actor) {
        AuditListRequest request = AuditListRequest.fromQuery(limit);
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.list(actor, request));
        } catch (DataAccessException | TransactionException exception) {
            throw new AuditUnavailableException();
        }
    }
}
