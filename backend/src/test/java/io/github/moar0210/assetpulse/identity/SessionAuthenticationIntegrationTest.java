package io.github.moar0210.assetpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ASSETPULSE_SESSION_COOKIE_SECURE=false")
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class SessionAuthenticationIntegrationTest {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void publicEndpointsRemainAvailableWhileCurrentSessionRequiresAuthentication()
            throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"available\"}"));

        mockMvc.perform(post("/api/v1/status")).andExpect(status().isMethodNotAllowed());

        csrf(null);

        mockMvc.perform(get(SESSION_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-ID"));
    }

    @ParameterizedTest
    @MethodSource("seededAccounts")
    void seededUsersAuthenticateWithDatabaseOwnedOrganisationAndRole(
            String email,
            String displayName,
            String organisationSlug,
            String roleCode,
            String roleDisplayName)
            throws Exception {
        CsrfExchange csrf = csrf(null);
        String anonymousSessionId = csrf.session().getId();

        MvcResult login = login(csrf, email, DEMO_PASSWORD, Map.of());

        assertThat(csrf.session().getId()).isNotEqualTo(anonymousSessionId);
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode response = objectMapper.readTree(login.getResponse().getContentAsString());
        assertThat(response.path("email").asText()).isEqualTo(email);
        assertThat(response.path("displayName").asText()).isEqualTo(displayName);
        assertThat(response.path("organisation").path("slug").asText()).isEqualTo(organisationSlug);
        assertThat(response.path("role").path("code").asText()).isEqualTo(roleCode);
        assertThat(response.path("role").path("displayName").asText()).isEqualTo(roleDisplayName);

        SecurityContext storedContext =
                (SecurityContext)
                        csrf.session()
                                .getAttribute(
                                        HttpSessionSecurityContextRepository
                                                .SPRING_SECURITY_CONTEXT_KEY);
        assertThat(storedContext.getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(
                        AuthenticatedActor.class,
                        actor -> {
                            assertThat(actor.getPassword()).isNull();
                            assertThat(actor.organisationSlug()).isEqualTo(organisationSlug);
                            assertThat(actor.roleCode()).isEqualTo(roleCode);
                        });
    }

    @Test
    void browserSuppliedScopeCannotChangeTheTrustedPrincipal() throws Exception {
        CsrfExchange csrf = csrf(null);
        login(csrf, "admin@northstar.example", DEMO_PASSWORD, Map.of());

        mockMvc.perform(
                        get(SESSION_PATH)
                                .session(csrf.session())
                                .queryParam(
                                        "organisationId", "00000000-0000-0000-0000-000000000002")
                                .header("X-Organisation-ID", "00000000-0000-0000-0000-000000000002")
                                .header("X-Role", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.organisation.id").value("00000000-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.organisation.slug").value("northstar-operations"))
                .andExpect(jsonPath("$.role.code").value("OPERATIONS_ADMIN"));

        CsrfExchange anonymousCsrf = csrf(null);
        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(anonymousCsrf.session())
                                .header(anonymousCsrf.headerName(), anonymousCsrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(
                                                        "admin@northstar.example",
                                                        DEMO_PASSWORD,
                                                        Map.of(
                                                                "organisationId",
                                                                "00000000-0000-0000-0000-000000000002")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void badCredentialsAreGenericAndNeverWrittenToLogs(CapturedOutput output) throws Exception {
        String submittedSecret = "NeverLogThisPassword-42";
        JsonNode unknown =
                problem(login(csrf(null), "missing@example.invalid", submittedSecret, Map.of()));
        JsonNode incorrect =
                problem(login(csrf(null), "admin@northstar.example", submittedSecret, Map.of()));

        assertThat(withoutCorrelation(unknown)).isEqualTo(withoutCorrelation(incorrect));
        assertThat(unknown.path("status").asInt()).isEqualTo(401);
        assertThat(unknown.path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(unknown.path("detail").asText())
                .isEqualTo("The email or password is incorrect.");
        assertThat(output.getAll()).doesNotContain(submittedSecret).doesNotContain("{bcrypt}");
    }

    @Test
    void loginAndLogoutRequireCsrfAndRotateTheToken() throws Exception {
        CsrfExchange csrf = csrf(null);

        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(csrf.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(
                                                        "admin@northstar.example",
                                                        DEMO_PASSWORD,
                                                        Map.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), "invalid-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(
                                                        "admin@northstar.example",
                                                        DEMO_PASSWORD,
                                                        Map.of()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        login(csrf, "admin@northstar.example", DEMO_PASSWORD, Map.of());

        mockMvc.perform(
                        delete(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        mockMvc.perform(get(SESSION_PATH).session(csrf.session())).andExpect(status().isOk());

        CsrfExchange authenticatedCsrf = csrf(csrf.session());
        assertThat(authenticatedCsrf.token()).isNotEqualTo(csrf.token());

        mockMvc.perform(
                        delete(SESSION_PATH)
                                .session(authenticatedCsrf.session())
                                .header(authenticatedCsrf.headerName(), authenticatedCsrf.token()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString(
                                                "ASSETPULSE_SESSION=")));

        assertThat(authenticatedCsrf.session().isInvalid()).isTrue();

        mockMvc.perform(get(SESSION_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        CsrfExchange postLogoutCsrf = csrf(null);
        assertThat(postLogoutCsrf.token()).isNotEqualTo(authenticatedCsrf.token());
    }

    @Test
    void authenticatedSessionCannotSwitchAccountsWithoutLogout() throws Exception {
        CsrfExchange csrf = csrf(null);
        login(csrf, "admin@northstar.example", DEMO_PASSWORD, Map.of());
        CsrfExchange authenticatedCsrf = csrf(csrf.session());

        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(authenticatedCsrf.session())
                                .header(authenticatedCsrf.headerName(), authenticatedCsrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(
                                                        "admin@riverside.example",
                                                        DEMO_PASSWORD,
                                                        Map.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_ALREADY_AUTHENTICATED"));

        mockMvc.perform(get(SESSION_PATH).session(authenticatedCsrf.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@northstar.example"))
                .andExpect(jsonPath("$.organisation.slug").value("northstar-operations"));
    }

    @Test
    void malformedAndOversizedLoginRequestsReturnNonLeakingProblems() throws Exception {
        CsrfExchange malformedCsrf = csrf(null);
        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(malformedCsrf.session())
                                .header(malformedCsrf.headerName(), malformedCsrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("The request body is not valid JSON for this operation."));

        CsrfExchange oversizedCsrf = csrf(null);
        String oversizedEmail = "x".repeat(255);
        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(oversizedCsrf.session())
                                .header(oversizedCsrf.headerName(), oversizedCsrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(
                                                        oversizedEmail, DEMO_PASSWORD, Map.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors.email").value("invalid"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        oversizedEmail))));
    }

    private CsrfExchange csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.get(CSRF_PATH).accept(MediaType.APPLICATION_JSON);
        if (session != null) {
            request.session(session);
        }

        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        MockHttpSession resolvedSession = (MockHttpSession) result.getRequest().getSession(false);

        assertThat(payload.fieldNames()).toIterable().containsExactly("headerName", "token");
        assertThat(resolvedSession).isNotNull();
        return new CsrfExchange(
                resolvedSession,
                payload.path("headerName").asText(),
                payload.path("token").asText());
    }

    private MvcResult login(
            CsrfExchange csrf, String email, String password, Map<String, Object> additionalFields)
            throws Exception {
        return mockMvc.perform(
                        post(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                loginBody(email, password, additionalFields))))
                .andReturn();
    }

    private Map<String, Object> loginBody(
            String email, String password, Map<String, Object> additionalFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.putAll(additionalFields);
        return body;
    }

    private JsonNode problem(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode withoutCorrelation(JsonNode problem) {
        JsonNode copy = problem.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("correlationId");
        return copy;
    }

    private static Stream<Arguments> seededAccounts() {
        return Stream.of(
                Arguments.of(
                        "admin@northstar.example",
                        "Nora Admin",
                        "northstar-operations",
                        "OPERATIONS_ADMIN",
                        "Operations Admin"),
                Arguments.of(
                        "technician@northstar.example",
                        "Theo Technician",
                        "northstar-operations",
                        "TECHNICIAN",
                        "Technician"),
                Arguments.of(
                        "viewer@northstar.example",
                        "Vera Viewer",
                        "northstar-operations",
                        "VIEWER",
                        "Viewer"),
                Arguments.of(
                        "admin@riverside.example",
                        "Riley Admin",
                        "riverside-manufacturing",
                        "OPERATIONS_ADMIN",
                        "Operations Admin"));
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}
}
