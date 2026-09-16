package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.audit.AuditAction;
import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.security.ApiProblemWriter;
import io.github.moar0210.assetpulse.security.SecurityConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(AlertController.class)
@Import({
    ApiProblemWriter.class,
    SecurityConfiguration.class,
    AlertStreamConfiguration.class,
    AlertStreamService.class
})
class AlertStreamSecurityTest {

    private static final String STREAM_PATH = "/api/v1/alerts/stream";
    private static final UUID NORTHSTAR_ID = UUID.randomUUID();
    private static final UUID RIVERSIDE_ID = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private AlertStreamService streamService;

    @MockitoBean private AlertQueryService queryService;
    @MockitoBean private AlertCommandService commandService;
    @MockitoBean private DatabaseUserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;

    @AfterEach
    void closeStreams() {
        streamService.closeAll();
    }

    @ParameterizedTest
    @MethodSource("authorisedRoles")
    void allThreeAuthorisedRolesCanOpenTheStream(String role) throws Exception {
        AuthenticatedActor actor = actor(NORTHSTAR_ID, role);

        MvcResult result =
                mockMvc.perform(
                                get(STREAM_PATH)
                                        .with(authentication(authenticationFor(actor)))
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Accel-Buffering", "no"))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("event:ready\ndata:{}\n\n");
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isOne();
        assertThat(streamService.subscriberCount(RIVERSIDE_ID)).isZero();
    }

    @Test
    void anonymousAndUnrecognisedRolesCannotOpenTheStream() throws Exception {
        mockMvc.perform(get(STREAM_PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        AuthenticatedActor unrecognised = actor(NORTHSTAR_ID, "AUDITOR");
        mockMvc.perform(
                        get(STREAM_PATH)
                                .with(authentication(authenticationFor(unrecognised)))
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isZero();
    }

    @Test
    void serializesOnlyTheExplicitChangeToTheMatchingTenantConnection() throws Exception {
        MvcResult northstar = openStream(actor(NORTHSTAR_ID, "VIEWER"));
        MvcResult riverside = openStream(actor(RIVERSIDE_ID, "OPERATIONS_ADMIN"));
        UUID alertId = UUID.randomUUID();

        streamService.publish(
                NORTHSTAR_ID, new AlertChangeEvent(alertId, AlertChangeType.OCCURRENCE_RECORDED));

        String expectedFrame =
                "event:alert-changed\ndata:{\"alertId\":\""
                        + alertId
                        + "\",\"changeType\":\"OCCURRENCE_RECORDED\"}\n\n";
        awaitResponseContains(northstar, expectedFrame);
        assertThat(northstar.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n" + expectedFrame)
                .doesNotContain("organisationId")
                .doesNotContain("telemetry")
                .doesNotContain("fingerprint");
        assertThat(riverside.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n");
    }

    @Test
    @DisplayName("AUTH-04, ALR-03: logout revokes only the originating session's alert streams")
    void logoutRevokesEveryStreamForTheSessionAndPreservesOtherSessions() throws Exception {
        AuthenticatedActor viewer = actor(NORTHSTAR_ID, "VIEWER");
        MockHttpSession endingSession = authenticatedSession(viewer);
        MvcResult firstEndingStream = openStream(endingSession);
        MvcResult secondEndingStream = openStream(endingSession);
        MvcResult healthyNorthstar = openStream(authenticatedSession(viewer));
        MvcResult healthyRiverside =
                openStream(authenticatedSession(actor(RIVERSIDE_ID, "OPERATIONS_ADMIN")));
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isEqualTo(3);

        mockMvc.perform(delete("/api/v1/session").session(endingSession).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(endingSession.isInvalid()).isTrue();
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isOne();
        assertThat(streamService.subscriberCount(RIVERSIDE_ID)).isOne();
        UUID northstarAlertId = UUID.randomUUID();
        UUID riversideAlertId = UUID.randomUUID();
        streamService.publish(
                NORTHSTAR_ID,
                new AlertChangeEvent(northstarAlertId, AlertChangeType.OCCURRENCE_RECORDED));
        streamService.publish(
                RIVERSIDE_ID,
                new AlertChangeEvent(riversideAlertId, AlertChangeType.OCCURRENCE_RECORDED));
        awaitResponseContains(healthyNorthstar, northstarAlertId.toString());
        awaitResponseContains(healthyRiverside, riversideAlertId.toString());
        assertThat(firstEndingStream.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n");
        assertThat(secondEndingStream.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n");
        assertThat(healthyNorthstar.getResponse().getContentAsString())
                .doesNotContain(riversideAlertId.toString());
        assertThat(healthyRiverside.getResponse().getContentAsString())
                .doesNotContain(northstarAlertId.toString());
    }

    @Test
    @DisplayName("AUTH-03, ALR-03: a CSRF-denied logout leaves the authenticated stream active")
    void rejectedLogoutDoesNotRevokeTheStream() throws Exception {
        MockHttpSession session = authenticatedSession(actor(NORTHSTAR_ID, "VIEWER"));
        MvcResult stream = openStream(session);

        mockMvc.perform(delete("/api/v1/session").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertThat(session.isInvalid()).isFalse();
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isOne();
        UUID alertId = UUID.randomUUID();
        streamService.publish(
                NORTHSTAR_ID, new AlertChangeEvent(alertId, AlertChangeType.OCCURRENCE_RECORDED));
        awaitResponseContains(stream, alertId.toString());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("AUTH-04, AUD-01, ALR-03: logout still revokes streams when its audit write fails")
    void auditFailureDoesNotKeepTheEndedSessionStreamAlive() throws Exception {
        AuthenticatedActor viewer = actor(NORTHSTAR_ID, "VIEWER");
        MockHttpSession session = authenticatedSession(viewer);
        MvcResult endedStream = openStream(session);
        MvcResult healthyStream = openStream(authenticatedSession(viewer));
        doThrow(new DataAccessResourceFailureException("Audit insert unavailable"))
                .when(auditService)
                .record(
                        eq(NORTHSTAR_ID),
                        eq(viewer.userId()),
                        eq(AuditAction.SESSION_ENDED),
                        eq(viewer.userId()),
                        anyString());

        mockMvc.perform(delete("/api/v1/session").session(session).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isOne();
        UUID alertId = UUID.randomUUID();
        streamService.publish(
                NORTHSTAR_ID, new AlertChangeEvent(alertId, AlertChangeType.OCCURRENCE_RECORDED));
        awaitResponseContains(healthyStream, alertId.toString());
        assertThat(endedStream.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n");
    }

    @Test
    @DisplayName(
            "AUTH-04, ALR-03: session invalidation revokes streams and rejects anonymous reconnect")
    void directSessionInvalidationRevokesTheStreamAndRequiresFreshAuthentication()
            throws Exception {
        MockHttpSession session = authenticatedSession(actor(NORTHSTAR_ID, "VIEWER"));
        String expiredSessionId = session.getId();
        MvcResult expiredStream = openStream(session);

        session.invalidate();

        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isZero();
        streamService.publish(
                NORTHSTAR_ID,
                new AlertChangeEvent(UUID.randomUUID(), AlertChangeType.OCCURRENCE_RECORDED));
        assertThat(expiredStream.getResponse().getContentAsString())
                .isEqualTo("event:ready\ndata:{}\n\n");
        mockMvc.perform(
                        get(STREAM_PATH)
                                .cookie(new Cookie("ASSETPULSE_SESSION", expiredSessionId))
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isZero();
    }

    private MockHttpSession authenticatedSession(AuthenticatedActor actor) {
        MockHttpSession session = new MockHttpSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticationFor(actor));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private MvcResult openStream(MockHttpSession session) throws Exception {
        return mockMvc.perform(
                        get(STREAM_PATH).session(session).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private MvcResult openStream(AuthenticatedActor actor) throws Exception {
        return mockMvc.perform(
                        get(STREAM_PATH)
                                .with(authentication(authenticationFor(actor)))
                                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private void awaitResponseContains(MvcResult result, String expected) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (result.getResponse().getContentAsString().contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(result.getResponse().getContentAsString()).contains(expected);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(AuthenticatedActor actor) {
        return UsernamePasswordAuthenticationToken.authenticated(
                actor, null, actor.getAuthorities());
    }

    private AuthenticatedActor actor(UUID organisationId, String role) {
        return new AuthenticatedActor(
                UUID.randomUUID(),
                role.toLowerCase() + "@example.test",
                "Test User",
                "unused-password",
                organisationId,
                "test-organisation",
                "Test Organisation",
                role,
                "Test Role");
    }

    private static Stream<String> authorisedRoles() {
        return Stream.of("OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER");
    }
}
