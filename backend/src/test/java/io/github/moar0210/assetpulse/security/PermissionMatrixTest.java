package io.github.moar0210.assetpulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.github.moar0210.assetpulse.audit.AuditService;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.identity.SessionAuthenticationService;
import io.github.moar0210.assetpulse.identity.SessionController;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SessionController.class)
@Import({ApiProblemWriter.class, SecurityConfiguration.class})
class PermissionMatrixTest {

    private static final String ADMIN = "OPERATIONS_ADMIN";
    private static final String TECHNICIAN = "TECHNICIAN";
    private static final String VIEWER = "VIEWER";
    private static final Set<String> ALL_ROLES = Set.of(ADMIN, TECHNICIAN, VIEWER);
    private static final Set<String> ADMIN_ONLY = Set.of(ADMIN);
    private static final Set<String> TECHNICIAN_ONLY = Set.of(TECHNICIAN);
    private static final String ID = "50000000-0000-0000-0000-000000000001";

    private static final List<Endpoint> ENDPOINTS =
            List.of(
                    read("/api/v1/dashboard", ALL_ROLES),
                    read("/api/v1/assets", ALL_ROLES),
                    read("/api/v1/assets/" + ID, ALL_ROLES),
                    read("/api/v1/sensors/" + ID + "/telemetry-readings", ALL_ROLES),
                    read("/api/v1/alerts", ALL_ROLES),
                    read("/api/v1/alerts/stream", ALL_ROLES),
                    read("/api/v1/alerts/" + ID, ALL_ROLES),
                    read("/api/v1/work-orders", ALL_ROLES),
                    read("/api/v1/work-orders/" + ID, ALL_ROLES),
                    read("/api/v1/audit-events", ADMIN_ONLY),
                    read("/api/v1/work-orders/eligible-technicians", ADMIN_ONLY),
                    read("/api/v1/processing-events/dead", ADMIN_ONLY),
                    command("/api/v1/alerts/" + ID + "/acknowledge", ADMIN_ONLY),
                    command("/api/v1/alerts/" + ID + "/resolve", ADMIN_ONLY),
                    command("/api/v1/work-orders", ADMIN_ONLY),
                    command("/api/v1/work-orders/" + ID + "/assign", ADMIN_ONLY),
                    command("/api/v1/processing-events/" + ID + "/retry", ADMIN_ONLY),
                    command("/api/v1/telemetry-batches", ADMIN_ONLY),
                    command("/api/v1/demo/reset", ADMIN_ONLY),
                    command("/api/v1/work-orders/" + ID + "/start", TECHNICIAN_ONLY),
                    command("/api/v1/work-orders/" + ID + "/complete", TECHNICIAN_ONLY));

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SessionAuthenticationService authenticationService;
    @MockitoBean private DatabaseUserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;

    @Test
    void auth04EveryProtectedRouteAdmitsOnlyItsDeclaredRoleSet() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            for (String role : ALL_ROLES) {
                MockHttpServletRequestBuilder request =
                        request(endpoint.method(), endpoint.path())
                                .with(user("matrix-user").roles(role));
                if (endpoint.method() == HttpMethod.POST) {
                    request.with(csrf());
                }

                int expectedStatus = endpoint.roles().contains(role) ? 404 : 403;
                mockMvc.perform(request)
                        .andExpect(
                                result ->
                                        assertThat(result.getResponse().getStatus())
                                                .as(
                                                        "%s %s for %s",
                                                        endpoint.method(), endpoint.path(), role)
                                                .isEqualTo(expectedStatus));
            }
        }
    }

    @Test
    void auth04EveryReadRouteRejectsAnonymousRequestsBeforeDispatch() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            if (endpoint.method() != HttpMethod.GET) {
                continue;
            }
            mockMvc.perform(request(endpoint.method(), endpoint.path()))
                    .andExpect(
                            result ->
                                    assertThat(result.getResponse().getStatus())
                                            .as("anonymous %s", endpoint.path())
                                            .isEqualTo(401))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    @Test
    void auth04HeadRequestsUseTheSameRoleMatrixAsReads() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            if (endpoint.method() != HttpMethod.GET) {
                continue;
            }
            for (String role : ALL_ROLES) {
                int expectedStatus = endpoint.roles().contains(role) ? 404 : 403;
                mockMvc.perform(
                                request(HttpMethod.HEAD, endpoint.path())
                                        .with(user("matrix-user").roles(role)))
                        .andExpect(
                                result ->
                                        assertThat(result.getResponse().getStatus())
                                                .as("HEAD %s for %s", endpoint.path(), role)
                                                .isEqualTo(expectedStatus));
            }
        }
    }

    @Test
    void auth04EveryMutationRequiresCsrfEvenForItsAuthorizedRole() throws Exception {
        for (Endpoint endpoint : ENDPOINTS) {
            if (endpoint.method() != HttpMethod.POST) {
                continue;
            }
            String authorizedRole = endpoint.roles().iterator().next();
            mockMvc.perform(
                            request(endpoint.method(), endpoint.path())
                                    .with(user("matrix-user").roles(authorizedRole)))
                    .andExpect(
                            result ->
                                    assertThat(result.getResponse().getStatus())
                                            .as("CSRF %s for %s", endpoint.path(), authorizedRole)
                                            .isEqualTo(403))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        }
    }

    @Test
    void auth04UnclassifiedApiRoutesAreDeniedInsteadOfInheritingAuthentication() throws Exception {
        for (String role : ALL_ROLES) {
            mockMvc.perform(
                            request(HttpMethod.GET, "/api/v1/unclassified")
                                    .with(user("matrix-user").roles(role)))
                    .andExpect(
                            result -> assertThat(result.getResponse().getStatus()).isEqualTo(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(
                            request(HttpMethod.POST, "/api/v1/unclassified")
                                    .with(user("matrix-user").roles(role))
                                    .with(csrf()))
                    .andExpect(
                            result -> assertThat(result.getResponse().getStatus()).isEqualTo(403))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    private static Endpoint read(String path, Set<String> roles) {
        return new Endpoint(HttpMethod.GET, path, roles);
    }

    private static Endpoint command(String path, Set<String> roles) {
        return new Endpoint(HttpMethod.POST, path, roles);
    }

    private record Endpoint(HttpMethod method, String path, Set<String> roles) {}
}
