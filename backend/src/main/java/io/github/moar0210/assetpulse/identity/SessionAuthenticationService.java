package io.github.moar0210.assetpulse.identity;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class SessionAuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final AuditService auditService;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticationService(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            AuditService auditService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.auditService = auditService;
    }

    public AuthenticatedActor authenticate(
            LoginRequest loginRequest,
            Authentication currentAuthentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (isAuthenticated(currentAuthentication)) {
            throw new AlreadyAuthenticatedException();
        }

        String email = loginRequest.email().strip().toLowerCase(Locale.ROOT);
        Authentication authentication;

        try {
            authentication =
                    authenticationManager.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(
                                    email, loginRequest.password()));
        } catch (InternalAuthenticationServiceException exception) {
            throw new AuthenticationUnavailableException();
        } catch (AuthenticationException exception) {
            try {
                auditService.recordAuthenticationFailure(CorrelationIdFilter.from(request));
            } catch (DataAccessException | TransactionException auditFailure) {
                throw new AuthenticationUnavailableException();
            }
            throw new AuthenticationFailedException();
        }

        if (!(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new IllegalStateException("Unexpected authenticated principal type");
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        try {
            auditService.record(
                    actor.organisationId(),
                    actor.userId(),
                    AuditAction.AUTHENTICATION_SUCCEEDED,
                    actor.userId(),
                    CorrelationIdFilter.from(request));
        } catch (DataAccessException | TransactionException exception) {
            throw new AuthenticationUnavailableException();
        }

        SecurityContext securityContext = securityContextHolderStrategy.createEmptyContext();
        securityContext.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);

        return actor;
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
