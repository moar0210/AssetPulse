package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.security.ApiProblemWriter;
import io.github.moar0210.assetpulse.security.SecurityConfiguration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
