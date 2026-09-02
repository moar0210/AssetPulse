package io.github.moar0210.assetpulse.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.moar0210.assetpulse.alerts.AlertCommandService;
import io.github.moar0210.assetpulse.alerts.AlertStateConflictException;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.security.CorrelationIdFilter;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DemoResetApiIntegrationTest {

    private static final String PATH = "/api/v1/demo/reset";
    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID NORTHSTAR_ALERT_ID =
            UUID.fromString("82000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ALERT_ID =
            UUID.fromString("82000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_WORK_ORDER_ID =
            UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_WORK_ORDER_ID =
            UUID.fromString("92000000-0000-0000-0000-000000000002");
    private static final Instant FIXTURE_TIME = Instant.parse("2026-08-31T10:00:00Z");

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
    @MockitoSpyBean private AlertCommandService alertCommandService;

    @BeforeEach
    void createResetFixtures() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
        jdbcClient.sql("TRUNCATE TABLE work_order_status_history").update();
        jdbcClient.sql("DELETE FROM work_order").update();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();

        insertAlert(NORTHSTAR_ALERT_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ID, 1);
        insertAlert(RIVERSIDE_ALERT_ID, RIVERSIDE_ID, RIVERSIDE_RULE_ID, 2);
        insertWorkOrder(NORTHSTAR_WORK_ORDER_ID, NORTHSTAR_ID, NORTHSTAR_ALERT_ID);
        insertWorkOrder(RIVERSIDE_WORK_ORDER_ID, RIVERSIDE_ID, RIVERSIDE_ALERT_ID);
    }

    @Test
    void administratorAtomicallyClosesOnlyTheTrustedOrganisationAndAuditsTheReset()
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post(PATH)
                                        .with(
                                                user(
                                                        users.loadUserByUsername(
                                                                "admin@northstar.example")))
                                        .with(csrf())
                                        .header("X-Organisation-ID", RIVERSIDE_ID)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.alertsResolved").value(1))
                        .andExpect(jsonPath("$.workOrdersCompleted").value(1))
                        .andReturn();

        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.fieldNames())
                .toIterable()
                .containsExactly("resetAt", "alertsResolved", "workOrdersCompleted");
        assertThat(Instant.parse(payload.path("resetAt").asText())).isNotNull();
        assertThat(alertStatus(NORTHSTAR_ALERT_ID)).isEqualTo("RESOLVED");
        assertThat(workOrderState(NORTHSTAR_WORK_ORDER_ID)).isEqualTo("DONE|3");
        assertThat(alertStatus(RIVERSIDE_ALERT_ID)).isEqualTo("OPEN");
        assertThat(workOrderState(RIVERSIDE_WORK_ORDER_ID)).isEqualTo("OPEN|0");
        assertThat(count("SELECT COUNT(*)::integer FROM alert_status_history")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*)::integer FROM work_order_status_history")).isEqualTo(3);
        assertThat(auditActions())
                .containsExactly(
                        "ALERT_ACKNOWLEDGED",
                        "ALERT_RESOLVED",
                        "DEMO_RESET",
                        "WORK_ORDER_ASSIGNED",
                        "WORK_ORDER_COMPLETED",
                        "WORK_ORDER_STARTED");
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT(subject_user_id, '|', actor_user_id)
                                        FROM audit_event
                                        WHERE action = 'DEMO_RESET'
                                        """)
                                .query(String.class)
                                .single())
                .isEqualTo(NORTHSTAR_ADMIN_ID + "|" + NORTHSTAR_ADMIN_ID);
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT COUNT(DISTINCT correlation_id)::integer FROM audit_event")
                                .query(Integer.class)
                                .single())
                .isOne();
        assertThat(
                        jdbcClient
                                .sql("SELECT correlation_id::text FROM audit_event LIMIT 1")
                                .query(String.class)
                                .single())
                .isEqualTo(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void anyLifecycleFailureRollsBackEveryResetMutationAndReturnsAGenericProblem()
            throws Exception {
        doThrow(new AlertStateConflictException())
                .when(alertCommandService)
                .resolve(
                        eq(NORTHSTAR_ID),
                        eq(NORTHSTAR_ADMIN_ID),
                        eq(NORTHSTAR_ALERT_ID),
                        anyString());

        mockMvc.perform(
                        post(PATH)
                                .with(user(users.loadUserByUsername("admin@northstar.example")))
                                .with(csrf())
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("DEMO_RESET_UNAVAILABLE"));

        assertThat(alertStatus(NORTHSTAR_ALERT_ID)).isEqualTo("OPEN");
        assertThat(workOrderState(NORTHSTAR_WORK_ORDER_ID)).isEqualTo("OPEN|0");
        assertThat(count("SELECT COUNT(*)::integer FROM alert_status_history")).isZero();
        assertThat(count("SELECT COUNT(*)::integer FROM work_order_status_history")).isZero();
        assertThat(count("SELECT COUNT(*)::integer FROM audit_event")).isZero();
    }

    private void insertAlert(UUID alertId, UUID organisationId, UUID ruleId, int fingerprintSeed) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id, organisation_id, threshold_rule_id, fingerprint,
                            first_occurred_at, last_occurred_at, cooldown_until,
                            created_at, updated_at
                        ) VALUES (
                            :alertId, :organisationId, :ruleId, :fingerprint,
                            :occurredAt, :occurredAt, :cooldownUntil,
                            :occurredAt, :occurredAt
                        )
                        """)
                .param("alertId", alertId)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", "%064x".formatted(fingerprintSeed))
                .param("occurredAt", FIXTURE_TIME.atOffset(ZoneOffset.UTC))
                .param("cooldownUntil", FIXTURE_TIME.plusSeconds(300).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertWorkOrder(UUID workOrderId, UUID organisationId, UUID alertId) {
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
                .param("createdAt", FIXTURE_TIME.atOffset(ZoneOffset.UTC))
                .update();
    }

    private String alertStatus(UUID alertId) {
        return jdbcClient
                .sql("SELECT status FROM alert WHERE id = :alertId")
                .param("alertId", alertId)
                .query(String.class)
                .single();
    }

    private String workOrderState(UUID workOrderId) {
        return jdbcClient
                .sql("SELECT CONCAT(status, '|', version) FROM work_order WHERE id = :workOrderId")
                .param("workOrderId", workOrderId)
                .query(String.class)
                .single();
    }

    private List<String> auditActions() {
        return jdbcClient
                .sql("SELECT action FROM audit_event ORDER BY action")
                .query(String.class)
                .list();
    }

    private int count(String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }
}
