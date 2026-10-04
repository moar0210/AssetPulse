package io.github.moar0210.assetpulse.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
    private static final UUID NORTHSTAR_TECHNICIAN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
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
    @MockitoSpyBean private DemoResetRepository repository;

    @BeforeEach
    void createResetFixtures() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
        jdbcClient.sql("TRUNCATE TABLE work_order_status_history").update();
        jdbcClient.sql("DELETE FROM work_order").update();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM threshold_rule WHERE rule_code LIKE 'RESET-BOUND-%'").update();

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

    @Test
    @DisplayName("WO-01–05, AUD-01: reset accepts exactly 100 active alerts and work orders")
    void exactMaximumResetCompletesEveryLifecycleAndPreservesTheOtherTenant() throws Exception {
        insertAdditionalIncidents(99, true, true);

        MvcResult result =
                mockMvc.perform(administratorPost(PATH))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.alertsResolved").value(100))
                        .andExpect(jsonPath("$.workOrdersCompleted").value(100))
                        .andReturn();

        assertThat(tenantCount("alert", NORTHSTAR_ID, "status = 'RESOLVED'")).isEqualTo(100);
        assertThat(tenantCount("work_order", NORTHSTAR_ID, "status = 'DONE' AND version = 3"))
                .isEqualTo(100);
        assertThat(alertStatus(RIVERSIDE_ALERT_ID)).isEqualTo("OPEN");
        assertThat(workOrderState(RIVERSIDE_WORK_ORDER_ID)).isEqualTo("OPEN|0");
        assertThat(count("SELECT COUNT(*)::integer FROM alert_status_history")).isEqualTo(200);
        assertThat(count("SELECT COUNT(*)::integer FROM work_order_status_history")).isEqualTo(300);
        assertThat(count("SELECT COUNT(*)::integer FROM audit_event")).isEqualTo(501);
        assertThat(count("SELECT COUNT(*)::integer FROM audit_event WHERE action = 'DEMO_RESET'"))
                .isOne();
        assertThat(
                        jdbcClient
                                .sql("SELECT DISTINCT correlation_id::text FROM audit_event")
                                .query(String.class)
                                .list())
                .containsExactly(result.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    @DisplayName("WO-01–05, AUD-01: 101 active alerts reject reset without effects")
    void alertLimitRejectsResetBeforeAnyLifecycleOrAuditWrite() throws Exception {
        insertAdditionalIncidents(100, true, false);
        assertThat(tenantCount("alert", NORTHSTAR_ID, "status IN ('OPEN', 'ACKNOWLEDGED')"))
                .isEqualTo(101);
        assertThat(tenantCount("work_order", NORTHSTAR_ID, "status <> 'DONE'")).isOne();

        assertLimitLeavesAllStateUnchanged();
    }

    @Test
    @DisplayName("WO-01–05, AUD-01: 101 active work orders reject reset without effects")
    void workOrderLimitRejectsResetIndependentlyOfTheAlertLimit() throws Exception {
        insertAdditionalIncidents(100, false, true);
        assertThat(tenantCount("alert", NORTHSTAR_ID, "status IN ('OPEN', 'ACKNOWLEDGED')"))
                .isOne();
        assertThat(tenantCount("work_order", NORTHSTAR_ID, "status <> 'DONE'")).isEqualTo(101);

        assertLimitLeavesAllStateUnchanged();
    }

    @Test
    @DisplayName("AUD-01: final reset audit failure rolls back every lifecycle, history and audit")
    void finalResetAuditConstraintFailureRollsBackAllEarlierWrites() throws Exception {
        List<String> alertsBefore = alertRows();
        List<String> workOrdersBefore = workOrderRows();
        jdbcClient
                .sql(
                        "ALTER TABLE audit_event ADD CONSTRAINT ck_demo_reset_test_audit "
                                + "CHECK (action <> 'DEMO_RESET') NOT VALID")
                .update();
        try {
            MvcResult result =
                    mockMvc.perform(administratorPost(PATH))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.code").value("DEMO_RESET_UNAVAILABLE"))
                            .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("ck_demo_reset_test_audit", "audit_event", "SQL", "23514");
            assertThat(result.getResolvedException())
                    .isInstanceOf(DemoResetUnavailableException.class)
                    .hasRootCauseInstanceOf(PSQLException.class);
            PSQLException databaseFailure =
                    (PSQLException)
                            NestedExceptionUtils.getMostSpecificCause(
                                    result.getResolvedException());
            assertThat(databaseFailure.getSQLState()).isEqualTo("23514");
            assertThat(databaseFailure.getServerErrorMessage()).isNotNull();
            assertThat(databaseFailure.getServerErrorMessage().getConstraint())
                    .isEqualTo("ck_demo_reset_test_audit");
            assertThat(databaseFailure.getServerErrorMessage().getDetail()).contains("DEMO_RESET");
            assertThat(alertRows()).isEqualTo(alertsBefore);
            assertThat(workOrderRows()).isEqualTo(workOrdersBefore);
            assertNoHistoryOrAudit();
        } finally {
            jdbcClient
                    .sql("ALTER TABLE audit_event DROP CONSTRAINT ck_demo_reset_test_audit")
                    .update();
        }
    }

    @Test
    @DisplayName("WO-03–05, AUD-01: reset row locks serialize competing commands without replay")
    void resetLocksSerializeCompetingAssignmentAndAlertCommandsWithoutReplay() throws Exception {
        CountDownLatch resetLocked = new CountDownLatch(1);
        CountDownLatch allowReset = new CountDownLatch(1);
        AtomicInteger resetBackendId = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            Object selected = invocation.callRealMethod();
                            resetBackendId.set(
                                    jdbcClient
                                            .sql("SELECT pg_backend_pid()")
                                            .query(Integer.class)
                                            .single());
                            resetLocked.countDown();
                            if (!allowReset.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Timed out waiting to finish reset");
                            }
                            return selected;
                        })
                .when(repository)
                .findActiveWorkOrdersForUpdate(NORTHSTAR_ID, 101);

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<MvcResult> reset =
                    executor.submit(() -> mockMvc.perform(administratorPost(PATH)).andReturn());
            assertThat(resetLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<MvcResult> assignment =
                    executor.submit(
                            () ->
                                    mockMvc.perform(
                                                    administratorPost(
                                                                    "/api/v1/work-orders/"
                                                                            + NORTHSTAR_WORK_ORDER_ID
                                                                            + "/assign")
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    "{\"technicianUserId\":\""
                                                                            + NORTHSTAR_TECHNICIAN_ID
                                                                            + "\",\"expectedVersion\":0}"))
                                            .andReturn());
            Future<MvcResult> acknowledgement =
                    executor.submit(
                            () ->
                                    mockMvc.perform(
                                                    administratorPost(
                                                            "/api/v1/alerts/"
                                                                    + NORTHSTAR_ALERT_ID
                                                                    + "/acknowledge"))
                                            .andReturn());

            assertThat(waitForBlockedUpdate("work_order", resetBackendId.get())).isTrue();
            assertThat(waitForBlockedUpdate("alert", resetBackendId.get())).isTrue();
            assertThat(assignment.isDone()).isFalse();
            assertThat(acknowledgement.isDone()).isFalse();
            allowReset.countDown();

            MvcResult resetResult = reset.get(10, TimeUnit.SECONDS);
            assertThat(resetResult.getResponse().getStatus()).isEqualTo(200);
            MvcResult assignmentResult = assignment.get(10, TimeUnit.SECONDS);
            assertThat(assignmentResult.getResponse().getStatus()).isEqualTo(409);
            assertThat(
                            objectMapper
                                    .readTree(assignmentResult.getResponse().getContentAsString())
                                    .path("code")
                                    .asText())
                    .isEqualTo("WORK_ORDER_STATE_CONFLICT");
            MvcResult acknowledgementResult = acknowledgement.get(10, TimeUnit.SECONDS);
            assertThat(acknowledgementResult.getResponse().getStatus()).isEqualTo(409);
            assertThat(
                            objectMapper
                                    .readTree(
                                            acknowledgementResult
                                                    .getResponse()
                                                    .getContentAsString())
                                    .path("code")
                                    .asText())
                    .isEqualTo("ALERT_STATE_CONFLICT");
            assertThat(alertStatus(NORTHSTAR_ALERT_ID)).isEqualTo("RESOLVED");
            assertThat(workOrderState(NORTHSTAR_WORK_ORDER_ID)).isEqualTo("DONE|3");
            assertThat(count("SELECT COUNT(*)::integer FROM alert_status_history")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*)::integer FROM work_order_status_history"))
                    .isEqualTo(3);
            assertThat(count("SELECT COUNT(*)::integer FROM audit_event")).isEqualTo(6);
            assertThat(
                            jdbcClient
                                    .sql("SELECT DISTINCT correlation_id::text FROM audit_event")
                                    .query(String.class)
                                    .list())
                    .containsExactly(
                            resetResult.getResponse().getHeader(CorrelationIdFilter.HEADER_NAME));
        } finally {
            allowReset.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertLimitLeavesAllStateUnchanged() throws Exception {
        List<String> alertsBefore = alertRows();
        List<String> workOrdersBefore = workOrderRows();

        mockMvc.perform(administratorPost(PATH))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("DEMO_RESET_LIMIT_EXCEEDED"));

        assertThat(alertRows()).isEqualTo(alertsBefore);
        assertThat(workOrderRows()).isEqualTo(workOrdersBefore);
        assertNoHistoryOrAudit();
    }

    private void assertNoHistoryOrAudit() {
        assertThat(count("SELECT COUNT(*)::integer FROM alert_status_history")).isZero();
        assertThat(count("SELECT COUNT(*)::integer FROM work_order_status_history")).isZero();
        assertThat(count("SELECT COUNT(*)::integer FROM audit_event")).isZero();
    }

    private void insertAdditionalIncidents(int additionalCount, boolean active, boolean withWork) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO threshold_rule (
                            id, organisation_id, sensor_id, rule_code, name, comparison,
                            threshold_value, cooldown_seconds, enabled, created_at, updated_at
                        )
                        SELECT
                            ('41000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                            organisation_id, sensor_id, 'RESET-BOUND-' || number,
                            'Reset boundary rule ' || number, comparison,
                            threshold_value, cooldown_seconds, enabled, created_at, updated_at
                        FROM threshold_rule
                        CROSS JOIN generate_series(1, :additionalCount) AS incidents(number)
                        WHERE id = :sourceRuleId
                        """)
                .param("additionalCount", additionalCount)
                .param("sourceRuleId", NORTHSTAR_RULE_ID)
                .update();
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id, organisation_id, threshold_rule_id, fingerprint, status,
                            first_occurred_at, last_occurred_at, cooldown_until,
                            created_at, updated_at
                        )
                        SELECT
                            ('83000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                            organisation_id,
                            ('41000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                            LPAD(TO_HEX(number + 2), 64, '0'), :status,
                            first_occurred_at, last_occurred_at, cooldown_until,
                            created_at, updated_at
                        FROM alert
                        CROSS JOIN generate_series(1, :additionalCount) AS incidents(number)
                        WHERE id = :sourceAlertId
                        """)
                .param("additionalCount", additionalCount)
                .param("sourceAlertId", NORTHSTAR_ALERT_ID)
                .param("status", active ? "OPEN" : "RESOLVED")
                .update();
        if (withWork) {
            jdbcClient
                    .sql(
                            """
                            INSERT INTO work_order (
                                id, organisation_id, alert_id, created_at, updated_at
                            )
                            SELECT
                                ('93000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                                organisation_id,
                                ('83000000-0000-0000-0000-' || LPAD(number::text, 12, '0'))::uuid,
                                created_at, updated_at
                            FROM work_order
                            CROSS JOIN generate_series(1, :additionalCount) AS incidents(number)
                            WHERE id = :sourceWorkOrderId
                            """)
                    .param("additionalCount", additionalCount)
                    .param("sourceWorkOrderId", NORTHSTAR_WORK_ORDER_ID)
                    .update();
        }
    }

    private int tenantCount(String table, UUID organisationId, String predicate) {
        return jdbcClient
                .sql(
                        "SELECT COUNT(*)::integer FROM "
                                + table
                                + " WHERE organisation_id = :organisationId AND "
                                + predicate)
                .param("organisationId", organisationId)
                .query(Integer.class)
                .single();
    }

    private MockHttpServletRequestBuilder administratorPost(String path) {
        return post(path)
                .with(user(users.loadUserByUsername("admin@northstar.example")))
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON);
    }

    private List<String> alertRows() {
        return jdbcClient
                .sql("SELECT row_to_json(alert)::text FROM alert ORDER BY organisation_id, id")
                .query(String.class)
                .list();
    }

    private List<String> workOrderRows() {
        return jdbcClient
                .sql(
                        "SELECT row_to_json(work_order)::text FROM work_order "
                                + "ORDER BY organisation_id, id")
                .query(String.class)
                .list();
    }

    private boolean waitForBlockedUpdate(String table, int resetBackendId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            int waiting =
                    jdbcClient
                            .sql(
                                    """
                                    SELECT COUNT(*)::integer
                                    FROM pg_stat_activity
                                    WHERE pid <> pg_backend_pid()
                                      AND wait_event_type = 'Lock'
                                      AND query ~ :queryPattern
                                      AND :resetBackendId = ANY(pg_blocking_pids(pid))
                                    """)
                            .param("queryPattern", "^\\s*UPDATE\\s+" + table + "\\s")
                            .param("resetBackendId", resetBackendId)
                            .query(Integer.class)
                            .single();
            if (waiting > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
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
