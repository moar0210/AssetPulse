package io.github.moar0210.assetpulse.assets;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetQueryService assetQueryService;

    public AssetController(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AssetListResponse> list(
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(assetQueryService.listForOrganisation(actor.organisationId()));
    }
}
