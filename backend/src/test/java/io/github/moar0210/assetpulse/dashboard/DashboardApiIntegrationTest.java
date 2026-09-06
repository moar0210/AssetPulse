package io.github.moar0210.assetpulse.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moar0210.assetpulse.audit.AuditAction;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class DashboardApiIntegrationTest {

    private static final String PATH = "/api/v1/dashboard";
    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_TECHNICIAN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID RIVERSIDE_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID OTHER_TECHNICIAN_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_ONE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_TWO_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID RIVERSIDE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID OPEN_ALERT_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID ACKNOWLEDGED_ALERT_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final UUID RESOLVED_ALERT_ONE_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000003");
    private static final UUID RESOLVED_ALERT_TWO_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000004");
    private static final UUID RIVERSIDE_ALERT_ID =
            UUID.fromString("51000000-0000-0000-0000-000000000005");
    private static final UUID OWN_ASSIGNED_WORK_ORDER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_IN_PROGRESS_WORK_ORDER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000002");
    private static final UUID UNASSIGNED_OPEN_WORK_ORDER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000003");
    private static final UUID OWN_DONE_WORK_ORDER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000004");
    private static final UUID RIVERSIDE_WORK_ORDER_ID =
            UUID.fromString("71000000-0000-0000-0000-000000000005");
    private static final Instant BASE_TIME = Instant.parse("2026-08-30T10:00:00Z");

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

    @BeforeEach
    void createDashboardFixtures() {
        insertOtherTechnician();

        insertAlert(OPEN_ALERT_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        insertAlert(ACKNOWLEDGED_ALERT_ID, NORTHSTAR_ID, NORTHSTAR_RULE_TWO_ID, "ACKNOWLEDGED", 2);
        insertAlert(RESOLVED_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "RESOLVED", 3);
        insertAlert(RESOLVED_ALERT_TWO_ID, NORTHSTAR_ID, NORTHSTAR_RULE_TWO_ID, "RESOLVED", 4);
        insertAlert(RIVERSIDE_ALERT_ID, RIVERSIDE_ID, RIVERSIDE_RULE_ID, "OPEN", 5);

        insertAssignedWorkOrder(
                OWN_ASSIGNED_WORK_ORDER_ID,
                NORTHSTAR_ID,
                OPEN_ALERT_ID,
                "ASSIGNED",
                1,
                NORTHSTAR_TECHNICIAN_ID);
        insertAssignedWorkOrder(
                OTHER_IN_PROGRESS_WORK_ORDER_ID,
                NORTHSTAR_ID,
                ACKNOWLEDGED_ALERT_ID,
                "IN_PROGRESS",
                2,
                OTHER_TECHNICIAN_ID);
        insertOpenWorkOrder(UNASSIGNED_OPEN_WORK_ORDER_ID, NORTHSTAR_ID, RESOLVED_ALERT_ONE_ID);
        insertAssignedWorkOrder(
                OWN_DONE_WORK_ORDER_ID,
                NORTHSTAR_ID,
                RESOLVED_ALERT_TWO_ID,
                "DONE",
                3,
                NORTHSTAR_TECHNICIAN_ID);
        insertOpenWorkOrder(RIVERSIDE_WORK_ORDER_ID, RIVERSIDE_ID, RIVERSIDE_ALERT_ID);

        insertWorkOrderActivity(
                1,
                NORTHSTAR_ID,
                NORTHSTAR_ADMIN_ID,
                AuditAction.WORK_ORDER_CREATED,
                OWN_ASSIGNED_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(1));
        insertAlertActivity(
                2,
                NORTHSTAR_ID,
                NORTHSTAR_ADMIN_ID,
                AuditAction.ALERT_RESOLVED,
                RESOLVED_ALERT_ONE_ID,
                BASE_TIME.plusSeconds(2));
        insertWorkOrderActivity(
                3,
                NORTHSTAR_ID,
                NORTHSTAR_ADMIN_ID,
                AuditAction.WORK_ORDER_CREATED,
                UNASSIGNED_OPEN_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(3));
        insertWorkOrderActivity(
                4,
                NORTHSTAR_ID,
                NORTHSTAR_TECHNICIAN_ID,
                AuditAction.WORK_ORDER_COMPLETED,
                OWN_DONE_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(4));
        insertWorkOrderActivity(
                5,
                NORTHSTAR_ID,
                NORTHSTAR_ADMIN_ID,
                AuditAction.WORK_ORDER_ASSIGNED,
                OWN_ASSIGNED_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(5));
        insertAlertActivity(
                6,
                NORTHSTAR_ID,
                NORTHSTAR_ADMIN_ID,
                AuditAction.ALERT_ACKNOWLEDGED,
                ACKNOWLEDGED_ALERT_ID,
                BASE_TIME.plusSeconds(6));
        insertWorkOrderActivity(
                7,
                NORTHSTAR_ID,
                OTHER_TECHNICIAN_ID,
                AuditAction.WORK_ORDER_STARTED,
                OTHER_IN_PROGRESS_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(7));
        insertWorkOrderActivity(
                8,
                RIVERSIDE_ID,
                RIVERSIDE_ADMIN_ID,
                AuditAction.WORK_ORDER_CREATED,
                RIVERSIDE_WORK_ORDER_ID,
                BASE_TIME.plusSeconds(100));
    }

    @Test
    void allSupportedRolesReceiveTenantCountsWithTechnicianOwnedWorkScoping() throws Exception {
        for (ExpectedDashboard expected :
                List.of(
                        new ExpectedDashboard("admin@northstar.example", 2, 1, 3),
                        new ExpectedDashboard("technician@northstar.example", 2, 1, 1),
                        new ExpectedDashboard("viewer@northstar.example", 2, 1, 3))) {
            JsonNode payload = getDashboard(expected.email());

            assertThat(payload.fieldNames())
                    .toIterable()
                    .containsExactly(
                            "assetCount",
                            "openAlertCount",
                            "activeWorkOrderCount",
                            "recentActivity");
            assertThat(payload.path("assetCount").asLong()).isEqualTo(expected.assetCount());
            assertThat(payload.path("openAlertCount").asLong())
                    .isEqualTo(expected.openAlertCount());
            assertThat(payload.path("activeWorkOrderCount").asLong())
                    .isEqualTo(expected.activeWorkOrderCount());
            assertThat(payload.path("recentActivity")).hasSize(5);
            assertSafeActivityShape(payload);
        }
    }

    @Test
    void recentActivityIsBoundedOrderedAndMatchesVisibleResources() throws Exception {
        JsonNode administrator = getDashboard("admin@northstar.example");
        assertActivities(
                administrator,
                List.of(
                        expectedActivity(
                                AuditAction.WORK_ORDER_STARTED, OTHER_IN_PROGRESS_WORK_ORDER_ID, 7),
                        expectedActivity(AuditAction.ALERT_ACKNOWLEDGED, ACKNOWLEDGED_ALERT_ID, 6),
                        expectedActivity(
                                AuditAction.WORK_ORDER_ASSIGNED, OWN_ASSIGNED_WORK_ORDER_ID, 5),
                        expectedActivity(
                                AuditAction.WORK_ORDER_COMPLETED, OWN_DONE_WORK_ORDER_ID, 4),
                        expectedActivity(
                                AuditAction.WORK_ORDER_CREATED, UNASSIGNED_OPEN_WORK_ORDER_ID, 3)));

        JsonNode technician = getDashboard("technician@northstar.example");
        assertActivities(
                technician,
                List.of(
                        expectedActivity(AuditAction.ALERT_ACKNOWLEDGED, ACKNOWLEDGED_ALERT_ID, 6),
                        expectedActivity(
                                AuditAction.WORK_ORDER_ASSIGNED, OWN_ASSIGNED_WORK_ORDER_ID, 5),
                        expectedActivity(
                                AuditAction.WORK_ORDER_COMPLETED, OWN_DONE_WORK_ORDER_ID, 4),
                        expectedActivity(AuditAction.ALERT_RESOLVED, RESOLVED_ALERT_ONE_ID, 2),
                        expectedActivity(
                                AuditAction.WORK_ORDER_CREATED, OWN_ASSIGNED_WORK_ORDER_ID, 1)));
        assertThat(technician.toString())
                .doesNotContain(
                        OTHER_IN_PROGRESS_WORK_ORDER_ID.toString(),
                        UNASSIGNED_OPEN_WORK_ORDER_ID.toString());
    }

    @Test
    void trustedOrganisationScopeIgnoresBrowserSuppliedTenantAndRole() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(PATH)
                                        .with(
                                                user(
                                                        users.loadUserByUsername(
                                                                "admin@northstar.example")))
                                        .header("X-Organisation-ID", RIVERSIDE_ID)
                                        .header("X-Role", "TECHNICIAN")
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.assetCount").value(2))
                        .andExpect(jsonPath("$.openAlertCount").value(1))
                        .andExpect(jsonPath("$.activeWorkOrderCount").value(3))
                        .andReturn();
        String payload = result.getResponse().getContentAsString();
        assertThat(payload)
                .doesNotContain(
                        RIVERSIDE_ID.toString(),
                        RIVERSIDE_ALERT_ID.toString(),
                        RIVERSIDE_WORK_ORDER_ID.toString());

        JsonNode riverside = getDashboard("admin@riverside.example");
        assertThat(riverside.path("assetCount").asLong()).isEqualTo(1);
        assertThat(riverside.path("openAlertCount").asLong()).isEqualTo(1);
        assertThat(riverside.path("activeWorkOrderCount").asLong()).isEqualTo(1);
        assertActivities(
                riverside,
                List.of(
                        expectedActivity(
                                AuditAction.WORK_ORDER_CREATED, RIVERSIDE_WORK_ORDER_ID, 100)));
        assertThat(riverside.toString())
                .doesNotContain(
                        NORTHSTAR_ID.toString(),
                        OPEN_ALERT_ID.toString(),
                        OWN_ASSIGNED_WORK_ORDER_ID.toString());
    }

    @Test
    void directAnonymousAndUnsupportedRoleRequestsAreDeniedWithoutDashboardData() throws Exception {
        mockMvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(content().string(not(containsString("assetCount"))));

        mockMvc.perform(
                        get(PATH)
                                .with(
                                        user(
                                                User.withUsername("unsupported@example.test")
                                                        .password("unused")
                                                        .roles("UNSUPPORTED")
                                                        .build())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(content().string(not(containsString("assetCount"))));
    }

    private JsonNode getDashboard(String email) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(PATH)
                                        .with(user(users.loadUserByUsername(email)))
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertSafeActivityShape(JsonNode payload) {
        payload.path("recentActivity")
                .forEach(
                        activity -> {
                            assertThat(activity.fieldNames())
                                    .toIterable()
                                    .containsExactly(
                                            "action", "subjectType", "subjectId", "occurredAt");
                            assertThat(Instant.parse(activity.path("occurredAt").asText()))
                                    .isNotNull();
                        });
        assertThat(payload.toString())
                .doesNotContain(
                        "actor",
                        "correlationId",
                        "organisationId",
                        NORTHSTAR_ADMIN_ID.toString(),
                        RIVERSIDE_ADMIN_ID.toString(),
                        "Nora Admin",
                        "Riley Admin");
    }

    private void assertActivities(JsonNode payload, List<ExpectedActivity> expectedActivities) {
        assertThat(payload.path("recentActivity")).hasSize(expectedActivities.size());
        for (int index = 0; index < expectedActivities.size(); index++) {
            ExpectedActivity expected = expectedActivities.get(index);
            JsonNode actual = payload.path("recentActivity").get(index);
            assertThat(actual.path("action").asText()).isEqualTo(expected.action().name());
            assertThat(actual.path("subjectType").asText())
                    .isEqualTo(expected.action().subjectType().name());
            assertThat(actual.path("subjectId").asText())
                    .isEqualTo(expected.subjectId().toString());
            assertThat(Instant.parse(actual.path("occurredAt").asText()))
                    .isEqualTo(BASE_TIME.plusSeconds(expected.secondsAfterBase()));
        }
        assertSafeActivityShape(payload);
    }

    private void insertOtherTechnician() {
        jdbcClient
                .sql(
                        """
                        INSERT INTO app_user (
                            id, organisation_id, email, display_name, password_hash, role_code,
                            created_at, updated_at
                        )
                        SELECT
                            :id, organisation_id, :email, :displayName, password_hash, role_code,
                            created_at, updated_at
                        FROM app_user
                        WHERE id = :sourceUserId
                        """)
                .param("id", OTHER_TECHNICIAN_ID)
                .param("email", "other-technician@northstar.example")
                .param("displayName", "Other Technician")
                .param("sourceUserId", NORTHSTAR_TECHNICIAN_ID)
                .update();
    }

    private void insertAlert(
            UUID alertId, UUID organisationId, UUID ruleId, String status, int fingerprintSeed) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id, organisation_id, threshold_rule_id, fingerprint, status,
                            first_occurred_at, last_occurred_at, cooldown_until,
                            created_at, updated_at
                        ) VALUES (
                            :alertId, :organisationId, :ruleId, :fingerprint, :status,
                            :occurredAt, :occurredAt, :cooldownUntil,
                            :occurredAt, :occurredAt
                        )
                        """)
                .param("alertId", alertId)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", "%064x".formatted(fingerprintSeed))
                .param("status", status)
                .param("occurredAt", BASE_TIME.minusSeconds(300).atOffset(ZoneOffset.UTC))
                .param("cooldownUntil", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertOpenWorkOrder(UUID workOrderId, UUID organisationId, UUID alertId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO work_order (
                            id, organisation_id, alert_id, created_at, updated_at
                        ) VALUES (
                            :workOrderId, :organisationId, :alertId, :createdAt, :createdAt
                        )
                        """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("createdAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertAssignedWorkOrder(
            UUID workOrderId,
            UUID organisationId,
            UUID alertId,
            String status,
            int version,
            UUID technicianId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO work_order (
                            id, organisation_id, alert_id, status, version,
                            assigned_technician_user_id, assigned_technician_role_code,
                            assigned_at, created_at, updated_at
                        ) VALUES (
                            :workOrderId, :organisationId, :alertId, :status, :version,
                            :technicianId, 'TECHNICIAN', :assignedAt, :createdAt, :updatedAt
                        )
                        """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("status", status)
                .param("version", version)
                .param("technicianId", technicianId)
                .param("createdAt", BASE_TIME.minusSeconds(10).atOffset(ZoneOffset.UTC))
                .param("assignedAt", BASE_TIME.minusSeconds(5).atOffset(ZoneOffset.UTC))
                .param("updatedAt", BASE_TIME.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertAlertActivity(
            int index,
            UUID organisationId,
            UUID actorId,
            AuditAction action,
            UUID alertId,
            Instant occurredAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO audit_event (
                            id, organisation_id, actor_user_id, action,
                            subject_alert_id, occurred_at, correlation_id
                        ) VALUES (
                            :id, :organisationId, :actorId, :action,
                            :subjectId, :occurredAt, :correlationId
                        )
                        """)
                .param("id", eventId(index))
                .param("organisationId", organisationId)
                .param("actorId", actorId)
                .param("action", action.name())
                .param("subjectId", alertId)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("correlationId", correlationId(index))
                .update();
    }

    private void insertWorkOrderActivity(
            int index,
            UUID organisationId,
            UUID actorId,
            AuditAction action,
            UUID workOrderId,
            Instant occurredAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO audit_event (
                            id, organisation_id, actor_user_id, action,
                            subject_work_order_id, occurred_at, correlation_id
                        ) VALUES (
                            :id, :organisationId, :actorId, :action,
                            :subjectId, :occurredAt, :correlationId
                        )
                        """)
                .param("id", eventId(index))
                .param("organisationId", organisationId)
                .param("actorId", actorId)
                .param("action", action.name())
                .param("subjectId", workOrderId)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("correlationId", correlationId(index))
                .update();
    }

    private static ExpectedActivity expectedActivity(
            AuditAction action, UUID subjectId, int secondsAfterBase) {
        return new ExpectedActivity(action, subjectId, secondsAfterBase);
    }

    private static UUID eventId(int index) {
        return UUID.fromString("a1000000-0000-0000-0000-%012d".formatted(index));
    }

    private static UUID correlationId(int index) {
        return UUID.fromString("c1000000-0000-0000-0000-%012d".formatted(index));
    }

    private record ExpectedDashboard(
            String email, long assetCount, long openAlertCount, long activeWorkOrderCount) {}

    private record ExpectedActivity(AuditAction action, UUID subjectId, int secondsAfterBase) {}
}
