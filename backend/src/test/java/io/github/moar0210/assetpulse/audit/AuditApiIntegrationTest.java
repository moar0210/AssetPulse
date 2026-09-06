package io.github.moar0210.assetpulse.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditApiIntegrationTest {

    private static final String PATH = "/api/v1/audit-events";
    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final Instant BASE_TIME = Instant.parse("2026-08-27T10:00:00Z");

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
    @Autowired private DatabaseUserDetailsService users;
    @Autowired private AuditService auditService;
    @Autowired private AuditQueryService queryService;

    @BeforeEach
    void clearAuditFixtures() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
    }

    @Test
    void aud01DefaultAndMaximumQueriesAreBoundedAndOrderedWithoutTenantOrPayloadLeakage()
            throws Exception {
        for (int index = 1; index <= 101; index++) {
            insertEvent(index, NORTHSTAR_ID, ADMIN_ID, BASE_TIME.plusSeconds(Math.min(index, 100)));
        }
        insertEvent(102, RIVERSIDE_ID, RIVERSIDE_ADMIN_ID, BASE_TIME.plusSeconds(1000));
        auditService.recordAuthenticationFailure(UUID.randomUUID().toString());

        JsonNode defaultPayload = list("admin@northstar.example", null);
        assertThat(defaultPayload.fieldNames()).toIterable().containsExactly("events", "limit");
        assertThat(defaultPayload.path("limit").asInt()).isEqualTo(50);
        assertThat(defaultPayload.path("events")).hasSize(50);
        assertThat(defaultPayload.path("events").get(0).path("id").asText())
                .isEqualTo(eventId(101).toString());
        assertThat(defaultPayload.path("events").get(1).path("id").asText())
                .isEqualTo(eventId(100).toString());
        assertThat(defaultPayload.path("events").get(49).path("id").asText())
                .isEqualTo(eventId(52).toString());
        JsonNode event = defaultPayload.path("events").get(0);
        assertThat(event.fieldNames())
                .toIterable()
                .containsExactly("id", "actor", "action", "subject", "occurredAt", "correlationId");
        assertThat(event.path("actor").fieldNames())
                .toIterable()
                .containsExactly("id", "displayName");
        assertThat(event.path("actor").path("id").asText()).isEqualTo(ADMIN_ID.toString());
        assertThat(event.path("actor").path("displayName").asText()).isEqualTo("Nora Admin");
        assertThat(event.path("action").asText()).isEqualTo("AUTHENTICATION_SUCCEEDED");
        assertThat(event.path("subject").fieldNames()).toIterable().containsExactly("type", "id");
        assertThat(event.path("subject").path("type").asText()).isEqualTo("USER");
        assertThat(event.path("subject").path("id").asText()).isEqualTo(ADMIN_ID.toString());
        assertThat(Instant.parse(event.path("occurredAt").asText()))
                .isEqualTo(BASE_TIME.plusSeconds(100));
        assertThat(event.path("correlationId").asText()).isEqualTo(correlationId(101).toString());

        JsonNode maximumPayload = list("admin@northstar.example", "100");
        assertThat(maximumPayload.path("events")).hasSize(100);
        assertThat(maximumPayload.path("events").get(99).path("id").asText())
                .isEqualTo(eventId(2).toString());
        assertThat(maximumPayload.toString())
                .doesNotContain(
                        RIVERSIDE_ID.toString(),
                        RIVERSIDE_ADMIN_ID.toString(),
                        "Riley Admin",
                        "AUTHENTICATION_FAILED",
                        "email",
                        "password",
                        "telemetry",
                        "session",
                        "organisationId");
    }

    @Test
    void aud01EmptyOrganisationAndRefreshReturnOnlyItsOwnEvents() throws Exception {
        insertEvent(1, RIVERSIDE_ID, RIVERSIDE_ADMIN_ID, BASE_TIME);
        auditService.recordAuthenticationFailure(UUID.randomUUID().toString());
        assertThat(list("admin@northstar.example", null).path("events")).isEmpty();
        auditService.record(
                NORTHSTAR_ID,
                ADMIN_ID,
                AuditAction.SESSION_ENDED,
                ADMIN_ID,
                UUID.randomUUID().toString());
        JsonNode refreshed = list("admin@northstar.example", null);
        assertThat(refreshed.path("events")).hasSize(1);
        assertThat(refreshed.path("events").get(0).path("action").asText())
                .isEqualTo("SESSION_ENDED");
    }

    @Test
    void aud01OnlyAdministratorsCanInspectGetAndHeadAndScopeCannotBeSpoofed() throws Exception {
        insertEvent(1, NORTHSTAR_ID, ADMIN_ID, BASE_TIME);
        insertEvent(2, RIVERSIDE_ID, RIVERSIDE_ADMIN_ID, BASE_TIME.plusSeconds(1));
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(head(PATH)).andExpect(status().isUnauthorized());

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            mockMvc.perform(
                            get(PATH)
                                    .with(user(users.loadUserByUsername(email)))
                                    .header("X-Role", "OPERATIONS_ADMIN"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(head(PATH).with(user(users.loadUserByUsername(email))))
                    .andExpect(status().isForbidden());
        }

        for (String email : List.of("admin@northstar.example", "admin@riverside.example")) {
            UUID expectedActor = email.contains("northstar") ? ADMIN_ID : RIVERSIDE_ADMIN_ID;
            UUID spoofedOrganisation = email.contains("northstar") ? RIVERSIDE_ID : NORTHSTAR_ID;
            mockMvc.perform(
                            get(PATH)
                                    .with(user(users.loadUserByUsername(email)))
                                    .header("X-Organisation-ID", spoofedOrganisation)
                                    .queryParam("organisationId", spoofedOrganisation.toString()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.events.length()").value(1))
                    .andExpect(jsonPath("$.events[0].actor.id").value(expectedActor.toString()));
            mockMvc.perform(head(PATH).with(user(users.loadUserByUsername(email))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        AuthenticatedActor viewer =
                (AuthenticatedActor) users.loadUserByUsername("viewer@northstar.example");
        assertThatThrownBy(() -> queryService.list(viewer, new AuditListRequest(50)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aud01InvalidLimitsHaveASafeStableProblemAndNoAuditSideEffects() throws Exception {
        for (String limit : List.of("", "0", "-1", "101", "many", "01", "+1", " 1 ", "1,2")) {
            MvcResult result =
                    mockMvc.perform(
                                    get(PATH)
                                            .with(
                                                    user(
                                                            users.loadUserByUsername(
                                                                    "admin@northstar.example")))
                                            .queryParam("limit", limit))
                            .andExpect(status().isBadRequest())
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.code").value("INVALID_AUDIT_QUERY"))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value(
                                                    "Provide a valid audit result limit from 1 to 100."))
                            .andReturn();
            assertThat(
                            objectMapper
                                    .readTree(result.getResponse().getContentAsString())
                                    .path("correlationId")
                                    .asText())
                    .isEqualTo(result.getResponse().getHeader("X-Correlation-ID"));
        }
        assertThat(
                        jdbcClient
                                .sql("SELECT COUNT(*)::integer FROM audit_event")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void aud01UnavailableStorageReturnsASafeProblemAndRecoversWithoutWrites() throws Exception {
        jdbcClient.sql("ALTER TABLE audit_event RENAME TO unavailable_audit_event").update();
        try {
            mockMvc.perform(
                            get(PATH)
                                    .with(
                                            user(
                                                    users.loadUserByUsername(
                                                            "admin@northstar.example"))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Audit events are temporarily unavailable. Try again later."));
        } finally {
            jdbcClient.sql("ALTER TABLE unavailable_audit_event RENAME TO audit_event").update();
        }
        assertThat(list("admin@northstar.example", null).path("events")).isEmpty();
    }

    @Test
    void aud01MutationRoutesAreDeniedByDefault() throws Exception {
        for (HttpMethod method :
                List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            mockMvc.perform(
                            request(method, PATH)
                                    .with(user(users.loadUserByUsername("admin@northstar.example")))
                                    .with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    private JsonNode list(String email, String limit) throws Exception {
        var request = get(PATH).with(user(users.loadUserByUsername(email)));
        if (limit != null) {
            request.queryParam("limit", limit);
        }
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void insertEvent(int index, UUID organisationId, UUID actorId, Instant occurredAt) {
        jdbcClient
                .sql(
                        """
                INSERT INTO audit_event (
                    id, organisation_id, actor_user_id, action, subject_user_id, occurred_at, correlation_id
                ) VALUES (
                    :id, :organisationId, :actorId, 'AUTHENTICATION_SUCCEEDED', :actorId, :occurredAt, :correlationId
                )
                """)
                .param("id", eventId(index))
                .param("organisationId", organisationId)
                .param("actorId", actorId)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("correlationId", correlationId(index))
                .update();
    }

    private static UUID eventId(int index) {
        return UUID.fromString("a0000000-0000-0000-0000-%012d".formatted(index));
    }

    private static UUID correlationId(int index) {
        return UUID.fromString("c0000000-0000-0000-0000-%012d".formatted(index));
    }
}
