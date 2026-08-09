package io.github.moar0210.assetpulse.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final SessionAuthenticationService authenticationService;

    public SessionController(SessionAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> currentSession(
            @AuthenticationPrincipal AuthenticatedActor actor) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SessionResponse.from(actor));
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(
            @Valid @RequestBody LoginRequest loginRequest,
            Authentication currentAuthentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthenticatedActor actor =
                authenticationService.authenticate(
                        loginRequest, currentAuthentication, request, response);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(SessionResponse.from(actor));
    }

    @GetMapping(path = "/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CsrfTokenResponse> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
    }
}
