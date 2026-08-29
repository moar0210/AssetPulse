package io.github.moar0210.assetpulse.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    @Autowired private JdbcClient jdbcClient;

    @BeforeEach
    void clearAuditEvents() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
    }

    @AfterEach
    void restoreAuditWrites() {
        jdbcClient
                .sql("DROP TRIGGER IF EXISTS reject_identity_audit_write ON audit_event")
                .update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS reject_identity_audit_write()").update();
    }

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
        JsonNode audit = assertAuthenticatedAudit(login, "AUTHENTICATION_SUCCEEDED", response);
        assertThat(OffsetDateTime.parse(audit.path("occurred_at").asText()).toInstant())
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(auditCount()).isOne();
        assertThat(audit.toString()).doesNotContain(email, DEMO_PASSWORD, "{bcrypt}", csrf.token());

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
        String suppliedCorrelation = "untrusted-correlation-value";
        MvcResult login =
                mockMvc.perform(
                                loginRequest(
                                                csrf,
                                                "admin@northstar.example",
                                                DEMO_PASSWORD,
                                                Map.of())
                                        .queryParam(
                                                "organisationId",
                                                "00000000-0000-0000-0000-000000000002")
                                        .header(
                                                "X-Organisation-ID",
                                                "00000000-0000-0000-0000-000000000002")
                                        .header("X-User-ID", "10000000-0000-0000-0000-000000000004")
                                        .header("X-Role", "VIEWER")
                                        .header("X-Correlation-ID", suppliedCorrelation))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode audit =
                assertAuthenticatedAudit(
                        login,
                        "AUTHENTICATION_SUCCEEDED",
                        objectMapper.readTree(login.getResponse().getContentAsString()));
        assertThat(audit.path("organisation_id").asText())
                .isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(audit.path("actor_user_id").asText())
                .isEqualTo("10000000-0000-0000-0000-000000000001");
        assertThat(audit.path("correlation_id").asText()).isNotEqualTo(suppliedCorrelation);

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
        assertThat(auditCount()).isOne();
    }

    @Test
    void badCredentialsAreGenericAndNeverWrittenToLogs(CapturedOutput output) throws Exception {
        String submittedSecret = "NeverLogThisPassword-42";
        CsrfExchange unknownCsrf = csrf(null);
        MvcResult unknownResult =
                mockMvc.perform(
                                loginRequest(
                                                unknownCsrf,
                                                "missing@example.invalid",
                                                submittedSecret,
                                                Map.of())
                                        .header(
                                                "X-Organisation-ID",
                                                "00000000-0000-0000-0000-000000000002")
                                        .header("X-User-ID", "10000000-0000-0000-0000-000000000004")
                                        .header("X-Correlation-ID", "forged-failure-correlation"))
                        .andReturn();
        MvcResult incorrectResult =
                login(csrf(null), "admin@northstar.example", submittedSecret, Map.of());
        JsonNode unknown = problem(unknownResult);
        JsonNode incorrect = problem(incorrectResult);

        assertThat(withoutCorrelation(unknown)).isEqualTo(withoutCorrelation(incorrect));
        assertThat(unknown.path("status").asInt()).isEqualTo(401);
        assertThat(unknown.path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(unknown.path("detail").asText())
                .isEqualTo("The email or password is incorrect.");
        for (MvcResult result : List.of(unknownResult, incorrectResult)) {
            JsonNode audit = auditFor(result);
            assertThat(audit.path("action").asText()).isEqualTo("AUTHENTICATION_FAILED");
            for (String field :
                    List.of(
                            "organisation_id",
                            "actor_user_id",
                            "subject_user_id",
                            "subject_alert_id",
                            "subject_work_order_id",
                            "subject_processing_event_id")) {
                assertThat(audit.path(field).isNull()).as(field).isTrue();
            }
            assertThat(audit.path("correlation_id").asText())
                    .isEqualTo(problem(result).path("correlationId").asText());
            assertThat(audit.toString())
                    .doesNotContain(
                            submittedSecret,
                            "missing@example.invalid",
                            "admin@northstar.example",
                            "{bcrypt}",
                            "forged-failure-correlation");
        }
        assertThat(auditCount()).isEqualTo(2);
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

        assertThat(auditCount()).isZero();
        MvcResult login = login(csrf, "admin@northstar.example", DEMO_PASSWORD, Map.of());

        mockMvc.perform(
                        delete(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(auditCount()).isOne();

        mockMvc.perform(get(SESSION_PATH).session(csrf.session())).andExpect(status().isOk());

        CsrfExchange authenticatedCsrf = csrf(csrf.session());
        assertThat(authenticatedCsrf.token()).isNotEqualTo(csrf.token());

        MvcResult logout =
                mockMvc.perform(
                                delete(SESSION_PATH)
                                        .session(authenticatedCsrf.session())
                                        .header(
                                                authenticatedCsrf.headerName(),
                                                authenticatedCsrf.token())
                                        .header(
                                                "X-Organisation-ID",
                                                "00000000-0000-0000-0000-000000000002")
                                        .header("X-User-ID", "10000000-0000-0000-0000-000000000004")
                                        .header("X-Correlation-ID", "forged-logout-correlation"))
                        .andExpect(status().isNoContent())
                        .andExpect(content().string(""))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(
                                header().string(
                                                "Set-Cookie",
                                                org.hamcrest.Matchers.containsString(
                                                        "ASSETPULSE_SESSION=")))
                        .andReturn();

        assertThat(authenticatedCsrf.session().isInvalid()).isTrue();
        JsonNode audit =
                assertAuthenticatedAudit(
                        logout,
                        "SESSION_ENDED",
                        objectMapper.readTree(login.getResponse().getContentAsString()));
        assertThat(audit.path("correlation_id").asText()).isNotEqualTo("forged-logout-correlation");
        assertThat(auditCount()).isEqualTo(2);

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
        assertThat(auditCount()).isOne();
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
        assertThat(auditCount()).isZero();
    }

    @Test
    void aud01AnonymousLogoutDoesNotInventAnActorOrAnAuditEvent() throws Exception {
        CsrfExchange anonymous = csrf(null);

        mockMvc.perform(
                        delete(SESSION_PATH)
                                .session(anonymous.session())
                                .header(anonymous.headerName(), anonymous.token()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(anonymous.session().isInvalid()).isTrue();
        assertThat(auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aud01AuditInsertOrCommitFailureCannotAdmitAnAuthenticatedSession(boolean failOnCommit)
            throws Exception {
        CsrfExchange anonymous = csrf(null);
        rejectAuditWrites(failOnCommit);

        MvcResult login = login(anonymous, "admin@northstar.example", DEMO_PASSWORD, Map.of());

        assertAuthenticationUnavailable(login);
        assertThat(
                        anonymous
                                .session()
                                .getAttribute(
                                        HttpSessionSecurityContextRepository
                                                .SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        mockMvc.perform(get(SESSION_PATH).session(anonymous.session()))
                .andExpect(status().isUnauthorized());
        assertThat(auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing@example.invalid", "admin@northstar.example"})
    void aud01AuditFailureDuringRejectedCredentialsRemainsAnonymous(String email) throws Exception {
        CsrfExchange anonymous = csrf(null);
        rejectAuditWrites(false);

        MvcResult login = login(anonymous, email, "Incorrect-password-42", Map.of());

        assertAuthenticationUnavailable(login);
        assertThat(
                        anonymous
                                .session()
                                .getAttribute(
                                        HttpSessionSecurityContextRepository
                                                .SPRING_SECURITY_CONTEXT_KEY))
                .isNull();
        assertThat(auditCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aud01AuditInsertOrCommitFailureStillInvalidatesTheLoggedOutSession(boolean failOnCommit)
            throws Exception {
        CsrfExchange anonymous = csrf(null);
        MvcResult login = login(anonymous, "admin@northstar.example", DEMO_PASSWORD, Map.of());
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        CsrfExchange authenticated = csrf(anonymous.session());
        rejectAuditWrites(failOnCommit);

        MvcResult logout =
                mockMvc.perform(
                                delete(SESSION_PATH)
                                        .session(authenticated.session())
                                        .header(authenticated.headerName(), authenticated.token()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(
                                content()
                                        .contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                        .andExpect(
                                jsonPath("$.detail")
                                        .value(
                                                "The session ended, but its audit record could not be saved."))
                        .andExpect(
                                header().string(
                                                "Set-Cookie",
                                                org.hamcrest.Matchers.containsString(
                                                        "ASSETPULSE_SESSION=")))
                        .andReturn();

        JsonNode problem = objectMapper.readTree(logout.getResponse().getContentAsString());
        assertThat(problem.path("correlationId").asText())
                .isEqualTo(logout.getResponse().getHeader("X-Correlation-ID"));
        assertThat(problem.toString())
                .doesNotContain("Simulated authentication audit failure", "INSERT", DEMO_PASSWORD);
        assertThat(authenticated.session().isInvalid()).isTrue();
        mockMvc.perform(get(SESSION_PATH)).andExpect(status().isUnauthorized());
        assertThat(auditCount()).isOne();
        assertThat(auditFor(login).path("action").asText()).isEqualTo("AUTHENTICATION_SUCCEEDED");
    }

    private JsonNode assertAuthenticatedAudit(MvcResult result, String action, JsonNode identity)
            throws Exception {
        JsonNode audit = auditFor(result);
        assertThat(audit.path("organisation_id").asText())
                .isEqualTo(identity.path("organisation").path("id").asText());
        assertThat(audit.path("actor_user_id").asText())
                .isEqualTo(identity.path("userId").asText());
        assertThat(audit.path("action").asText()).isEqualTo(action);
        assertThat(audit.path("subject_user_id").asText())
                .isEqualTo(identity.path("userId").asText());
        assertThat(audit.path("subject_alert_id").isNull()).isTrue();
        assertThat(audit.path("subject_work_order_id").isNull()).isTrue();
        assertThat(audit.path("subject_processing_event_id").isNull()).isTrue();
        return audit;
    }

    private JsonNode auditFor(MvcResult result) throws Exception {
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-ID"));
        JsonNode audit =
                objectMapper.readTree(
                        jdbcClient
                                .sql(
                                        """
                        SELECT row_to_json(audit_record)::text
                        FROM audit_event audit_record
                        WHERE correlation_id = :correlationId
                        """)
                                .param("correlationId", correlationId)
                                .query(String.class)
                                .single());
        assertThat(audit.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "id",
                        "organisation_id",
                        "actor_user_id",
                        "action",
                        "subject_user_id",
                        "subject_alert_id",
                        "subject_work_order_id",
                        "subject_processing_event_id",
                        "occurred_at",
                        "correlation_id");
        assertThat(audit.path("correlation_id").asText()).isEqualTo(correlationId.toString());
        return audit;
    }

    private int auditCount() {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM audit_event")
                .query(Integer.class)
                .single();
    }

    private void assertAuthenticationUnavailable(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("code").asText()).isEqualTo("AUTHENTICATION_UNAVAILABLE");
        assertThat(problem.path("correlationId").asText())
                .isEqualTo(result.getResponse().getHeader("X-Correlation-ID"));
        assertThat(problem.toString())
                .doesNotContain("Simulated authentication audit failure", "INSERT", DEMO_PASSWORD);
    }

    private void rejectAuditWrites(boolean failOnCommit) {
        jdbcClient
                .sql(
                        """
                CREATE FUNCTION reject_identity_audit_write() RETURNS TRIGGER
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated authentication audit failure';
                END;
                $$
                """)
                .update();
        jdbcClient
                .sql(
                        failOnCommit
                                ? """
                CREATE CONSTRAINT TRIGGER reject_identity_audit_write
                AFTER INSERT ON audit_event DEFERRABLE INITIALLY DEFERRED
                FOR EACH ROW EXECUTE FUNCTION reject_identity_audit_write()
                """
                                : """
                CREATE TRIGGER reject_identity_audit_write
                BEFORE INSERT ON audit_event
                FOR EACH ROW EXECUTE FUNCTION reject_identity_audit_write()
                """)
                .update();
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
        return mockMvc.perform(loginRequest(csrf, email, password, additionalFields)).andReturn();
    }

    private MockHttpServletRequestBuilder loginRequest(
            CsrfExchange csrf, String email, String password, Map<String, Object> additionalFields)
            throws Exception {
        return post(SESSION_PATH)
                .session(csrf.session())
                .header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                        objectMapper.writeValueAsBytes(
                                loginBody(email, password, additionalFields)));
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
