package io.github.moar0210.assetpulse.observability;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public final class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startedAt = System.nanoTime();
        ActorContext actorAtStart = currentActor();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            ActorContext actorAtEnd = currentActor();
            ActorContext actor = actorAtEnd != null ? actorAtEnd : actorAtStart;
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            double durationMillis =
                    TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt) / 1_000.0;

            LoggingEventBuilder event =
                    LOGGER.atInfo()
                            .addKeyValue("event", "http_request")
                            .addKeyValue("http_method", request.getMethod())
                            .addKeyValue("http_route", routeTemplate(request))
                            .addKeyValue("http_status", status)
                            .addKeyValue("duration_ms", durationMillis);
            if (actor != null) {
                event.addKeyValue("actor_id", actor.userId())
                        .addKeyValue("organisation_id", actor.organisationId())
                        .addKeyValue("actor_role", actor.roleCode());
            }
            event.log("HTTP request completed");
        }
    }

    private static ActorContext currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            return null;
        }
        return new ActorContext(actor.userId(), actor.organisationId(), actor.roleCode());
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (value instanceof String route && !route.isBlank()) {
            return route;
        }
        String path = request.getRequestURI();
        if (path.equals("/api") || path.startsWith("/api/")) {
            return "/api/**";
        }
        if (path.equals("/error")) {
            return "/error";
        }
        return "unmatched";
    }

    private record ActorContext(UUID userId, UUID organisationId, String roleCode) {}
}
