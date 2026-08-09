package io.github.moar0210.assetpulse.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
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

@Service
public class SessionAuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticationService(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
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
            throw new AuthenticationFailedException();
        }

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

        SecurityContext securityContext = securityContextHolderStrategy.createEmptyContext();
        securityContext.setAuthentication(authentication);
        securityContextHolderStrategy.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);

        if (authentication.getPrincipal() instanceof AuthenticatedActor actor) {
            return actor;
        }

        throw new IllegalStateException("Unexpected authenticated principal type");
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
