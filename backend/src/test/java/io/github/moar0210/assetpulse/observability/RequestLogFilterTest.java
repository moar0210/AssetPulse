package io.github.moar0210.assetpulse.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.security.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

class RequestLogFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsCorrelationRouteAndServerDerivedActorContextWithoutRequestSecrets() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID organisationId = UUID.randomUUID();
        AuthenticatedActor actor =
                new AuthenticatedActor(
                        userId,
                        "sensitive-email@example.test",
                        "Sensitive Display Name",
                        "sensitive-password",
                        organisationId,
                        "sensitive-slug",
                        "Sensitive Organisation Name",
                        "VIEWER",
                        "Viewer");
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                actor, null, actor.getAuthorities()));

        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/assets/sensitive-object-id");
        request.setQueryString("password=sensitive-query-secret");
        request.addHeader("Authorization", "Bearer sensitive-token");
        request.setContent("sensitive-request-body".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        appender.start();
        logger.addAppender(appender);
        new CorrelationIdFilter()
                .doFilter(
                        request,
                        response,
                        (correlatedRequest, correlatedResponse) ->
                                new RequestLogFilter()
                                        .doFilter(
                                                correlatedRequest,
                                                correlatedResponse,
                                                (loggedRequest, loggedResponse) -> {
                                                    loggedRequest.setAttribute(
                                                            HandlerMapping
                                                                    .BEST_MATCHING_PATTERN_ATTRIBUTE,
                                                            "/api/v1/assets/{assetId}");
                                                    ((MockHttpServletResponse) loggedResponse)
                                                            .setStatus(204);
                                                }));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        Map<String, Object> values =
                event.getKeyValuePairs().stream()
                        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMDCPropertyMap().get("correlation_id")).matches("[0-9a-f-]{36}");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo(event.getMDCPropertyMap().get("correlation_id"));
        assertThat(values)
                .containsEntry("event", "http_request")
                .containsEntry("http_method", "POST")
                .containsEntry("http_route", "/api/v1/assets/{assetId}")
                .containsEntry("http_status", 204)
                .containsEntry("actor_id", userId)
                .containsEntry("organisation_id", organisationId)
                .containsEntry("actor_role", "VIEWER");

        String emitted = event.getFormattedMessage() + values + event.getMDCPropertyMap();
        assertThat(emitted)
                .doesNotContain("sensitive-email")
                .doesNotContain("Sensitive Display")
                .doesNotContain("sensitive-password")
                .doesNotContain("sensitive-query")
                .doesNotContain("sensitive-token")
                .doesNotContain("sensitive-request-body")
                .doesNotContain("sensitive-object-id")
                .doesNotContain("sensitive-slug")
                .doesNotContain("Sensitive Organisation");
    }
}
