package io.github.moar0210.assetpulse.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.moar0210.assetpulse.audit.AuditService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

class SessionAuthenticationServiceRateLimitTest {

    private AuthenticationManager authenticationManager;
    private AuditService auditService;
    private SessionAuthenticationService service;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        auditService = mock(AuditService.class);
        LoginFailureLimiter limiter =
                new LoginFailureLimiter(
                        2,
                        Duration.ofMinutes(1),
                        100,
                        new FixedClock(Instant.parse("2026-08-31T12:00:00Z")));
        service =
                new SessionAuthenticationService(
                        authenticationManager,
                        mock(SecurityContextRepository.class),
                        mock(SessionAuthenticationStrategy.class),
                        auditService,
                        limiter,
                        new LoginClientAddressResolver());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blockedAttemptDoesNotReachAuthenticationAndForwardedHeadersCannotBypassTheKey() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertCredentialFailure(" User@Example.test ", "198.51.100.1");
        assertCredentialFailure("user@example.test", "198.51.100.2");

        assertThatThrownBy(() -> authenticate("USER@example.test", "198.51.100.3"))
                .isInstanceOf(LoginRateLimitExceededException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 60L);
        verify(authenticationManager, org.mockito.Mockito.times(2))
                .authenticate(any(Authentication.class));
    }

    @Test
    void authenticationAndAuditInfrastructureFailuresDoNotConsumeTheLimit() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new InternalAuthenticationServiceException("database unavailable"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> authenticate("user@example.test", null))
                    .isInstanceOf(AuthenticationUnavailableException.class);
        }

        verify(authenticationManager, org.mockito.Mockito.times(3))
                .authenticate(any(Authentication.class));
        verify(auditService, never()).recordAuthenticationFailure(anyString());

        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));
        doThrow(new DataAccessResourceFailureException("audit unavailable"))
                .when(auditService)
                .recordAuthenticationFailure(anyString());

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> authenticate("other@example.test", null))
                    .isInstanceOf(AuthenticationUnavailableException.class);
        }

        verify(authenticationManager, org.mockito.Mockito.times(6))
                .authenticate(any(Authentication.class));
    }

    @Test
    void successfulAuthenticationClearsEarlierFailures() {
        Authentication authenticated = authenticationFor(actor());
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"))
                .thenReturn(authenticated)
                .thenThrow(new BadCredentialsException("invalid"))
                .thenThrow(new BadCredentialsException("invalid"));

        assertCredentialFailure("user@example.test", null);
        authenticate("user@example.test", null);
        assertCredentialFailure("user@example.test", null);
        assertCredentialFailure("user@example.test", null);

        assertThatThrownBy(() -> authenticate("user@example.test", null))
                .isInstanceOf(LoginRateLimitExceededException.class);
        verify(authenticationManager, org.mockito.Mockito.times(4))
                .authenticate(any(Authentication.class));
    }

    @Test
    void trustedProxyClientAddressesKeepIndependentFailureBuckets() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.1"))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.2"))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.1"))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.2"))
                .isInstanceOf(AuthenticationFailedException.class);

        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.1"))
                .isInstanceOf(LoginRateLimitExceededException.class);
        assertThatThrownBy(() -> authenticateProxied("user@example.test", "198.51.100.2"))
                .isInstanceOf(LoginRateLimitExceededException.class);
        verify(authenticationManager, org.mockito.Mockito.times(4))
                .authenticate(any(Authentication.class));
    }

    private void assertCredentialFailure(String email, String forwardedFor) {
        assertThatThrownBy(() -> authenticate(email, forwardedFor))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    private AuthenticatedActor authenticate(String email, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return service.authenticate(
                new LoginRequest(email, "incorrect-or-test-password"),
                null,
                request,
                new MockHttpServletResponse());
    }

    private AuthenticatedActor authenticateProxied(String email, String clientAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader(
                LoginClientAddressResolver.TRUSTED_PROXY_CLIENT_ADDRESS_HEADER, clientAddress);
        return service.authenticate(
                new LoginRequest(email, "incorrect-or-test-password"),
                null,
                request,
                new MockHttpServletResponse());
    }

    private Authentication authenticationFor(AuthenticatedActor actor) {
        return UsernamePasswordAuthenticationToken.authenticated(
                actor, null, actor.getAuthorities());
    }

    private AuthenticatedActor actor() {
        return new AuthenticatedActor(
                UUID.randomUUID(),
                "user@example.test",
                "Test User",
                "unused-password",
                UUID.randomUUID(),
                "test-organisation",
                "Test Organisation",
                "VIEWER",
                "Viewer");
    }

    private static final class FixedClock extends Clock {

        private final Instant instant;

        private FixedClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
