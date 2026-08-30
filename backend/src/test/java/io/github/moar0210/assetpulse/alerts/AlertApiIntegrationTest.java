package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ASSETPULSE_SESSION_COOKIE_SECURE=false")
@AutoConfigureMockMvc
@Testcontainers
class AlertApiIntegrationTest {

    private static final String ALERTS_PATH = "/api/v1/alerts";
    private static final String ALERT_STREAM_PATH = ALERTS_PATH + "/stream";
    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID NORTHSTAR_ASSET_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID NORTHSTAR_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000004");
    private static final UUID MISSING_ALERT_ID =
            UUID.fromString("99999999-0000-0000-0000-000000000001");

    private static final String NORTHSTAR_FINGERPRINT =
            "1f4c1d7982a9b538ce9ee20182718662f1c82686e9e424e0679ff1bed54a4086";
    private static final String RIVERSIDE_FINGERPRINT =
            "46dc6e2545a33de010860661a7dc358ea6638b51e3dd3d42e19b566828a3e12a";
    private static final Instant FIRST_OCCURRED_AT = Instant.parse("2026-08-21T08:00:00Z");
    private static final Instant LAST_OCCURRED_AT = Instant.parse("2026-08-21T08:04:00Z");
    private static final Instant COOLDOWN_UNTIL = Instant.parse("2026-08-21T08:09:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-21T09:00:00Z");

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
    @Autowired private AlertCommandService commandService;
    @Autowired private AlertStreamService streamService;

    @BeforeEach
    void clearAlertsAndImmutableHistory() {
        streamService.closeAll();
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();
    }

    @ParameterizedTest
    @MethodSource("seededNorthstarRoles")
    void everySeededRoleCanOpenTheTenantScopedAlertStream(String email) throws Exception {
        MvcResult stream =
                mockMvc.perform(
                                get(ALERT_STREAM_PATH)
                                        .session(login(email).session())
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Role", "OPERATIONS_ADMIN")
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Accel-Buffering", "no"))
                        .andReturn();

        assertThat(stream.getResponse().getContentAsString()).isEqualTo("event:ready\ndata:{}\n\n");
        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isOne();
        assertThat(streamService.subscriberCount(RIVERSIDE_ID)).isZero();
        streamService.closeAll();
    }

    @Test
    void anonymousUsersCannotOpenTheAlertStream() throws Exception {
        mockMvc.perform(get(ALERT_STREAM_PATH).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(streamService.subscriberCount(NORTHSTAR_ID)).isZero();
        assertThat(streamService.subscriberCount(RIVERSIDE_ID)).isZero();
    }

    @Test
    void aCommittedStatusChangeInvalidatesOnlyTheMatchingTenantStream() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        AuthenticatedSession northstarAdmin = login("admin@northstar.example");
        MvcResult northstarStream = openStream(northstarAdmin.session());
        MvcResult riversideStream = openStream(login("admin@riverside.example").session());

        try {
            mockMvc.perform(command(northstarAdmin, NORTHSTAR_ALERT_ID, "acknowledge"))
                    .andExpect(status().isOk());

            String expectedChange =
                    "event:alert-changed\ndata:{\"alertId\":\""
                            + NORTHSTAR_ALERT_ID
                            + "\",\"changeType\":\"STATUS_CHANGED\"}\n\n";
            awaitStreamContains(northstarStream, expectedChange);
            assertThat(northstarStream.getResponse().getContentAsString())
                    .containsOnlyOnce(expectedChange)
                    .doesNotContain("organisationId")
                    .doesNotContain(NORTHSTAR_FINGERPRINT)
                    .doesNotContain("telemetry");
            assertThat(riversideStream.getResponse().getContentAsString())
                    .isEqualTo("event:ready\ndata:{}\n\n");

            mockMvc.perform(command(northstarAdmin, NORTHSTAR_ALERT_ID, "acknowledge"))
                    .andExpect(status().isConflict());
            assertThat(northstarStream.getResponse().getContentAsString())
                    .containsOnlyOnce(expectedChange);
        } finally {
            streamService.closeAll();
        }
    }

    @Test
    void defaultAndMaximumLimitsAreBoundedAndUseLastOccurrenceThenIdOrder() throws Exception {
        insertResolvedAlertRange(101);
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                99,
                LAST_OCCURRED_AT.plusSeconds(10_000));
        MockHttpSession session = login("viewer@northstar.example").session();

        JsonNode defaultPayload = listAlerts(session, null);

        assertThat(defaultPayload.fieldNames()).toIterable().containsExactly("alerts", "limit");
        assertThat(defaultPayload.path("limit").asInt()).isEqualTo(50);
        assertThat(defaultPayload.path("alerts").size()).isEqualTo(50);
        assertThat(defaultPayload.path("alerts").get(0).path("id").asText())
                .isEqualTo(generatedAlertId(100).toString());
        assertThat(defaultPayload.path("alerts").get(1).path("id").asText())
                .isEqualTo(generatedAlertId(101).toString());
        assertThat(defaultPayload.path("alerts").get(49).path("id").asText())
                .isEqualTo(generatedAlertId(52).toString());

        JsonNode maximumPayload = listAlerts(session, "100");

        assertThat(maximumPayload.path("limit").asInt()).isEqualTo(100);
        assertThat(maximumPayload.path("alerts").size()).isEqualTo(100);
        assertThat(maximumPayload.path("alerts").get(99).path("id").asText())
                .isEqualTo(generatedAlertId(2).toString());
        assertThat(maximumPayload.toString())
                .doesNotContain(generatedAlertId(1).toString())
                .doesNotContain(RIVERSIDE_ALERT_ID.toString());
    }

    @ParameterizedTest
    @MethodSource("seededNorthstarRoles")
    void everySeededRoleReadsTheExactTenantScopedQueueAndDetail(String email) throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                7,
                LAST_OCCURRED_AT.plusSeconds(60));
        MockHttpSession session = login(email).session();

        JsonNode list = listAlerts(session, null);
        JsonNode summary = list.path("alerts").get(0);

        assertThat(list.path("alerts").size()).isOne();
        assertThat(summary.fieldNames())
                .toIterable()
                .containsExactly("id", "status", "occurrenceCount", "lastOccurredAt", "context");
        assertThat(summary.path("id").asText()).isEqualTo(NORTHSTAR_ALERT_ID.toString());
        assertThat(summary.path("status").asText()).isEqualTo("OPEN");
        assertThat(summary.path("occurrenceCount").asLong()).isEqualTo(3);
        assertContext(summary.path("context"));
        assertNoInternalOrForeignData(list);

        JsonNode detail = detail(session, NORTHSTAR_ALERT_ID);

        assertThat(detail.path("id").asText()).isEqualTo(NORTHSTAR_ALERT_ID.toString());
        assertThat(detail.path("history")).isEmpty();
        assertContext(detail.path("context"));
        assertNoInternalOrForeignData(detail);
    }

    @Test
    void anOrganisationWithNoAlertsReceivesAnExplicitEmptyQueue() throws Exception {
        JsonNode payload = listAlerts(login("viewer@northstar.example").session(), null);

        assertThat(payload.fieldNames()).toIterable().containsExactly("alerts", "limit");
        assertThat(payload.path("limit").asInt()).isEqualTo(50);
        assertThat(payload.path("alerts").isArray()).isTrue();
        assertThat(payload.path("alerts")).isEmpty();
    }

    @Test
    void detailHasExactContextAndChronologicalImmutableActorHistory() throws Exception {
        insertNorthstarAlert(AlertStatus.RESOLVED);
        insertHistory(
                NORTHSTAR_ID,
                NORTHSTAR_ALERT_ID,
                2,
                AlertStatus.ACKNOWLEDGED,
                AlertStatus.RESOLVED,
                NORTHSTAR_ADMIN_ID,
                Instant.parse("2026-08-21T09:10:00Z"));
        insertHistory(
                NORTHSTAR_ID,
                NORTHSTAR_ALERT_ID,
                1,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                NORTHSTAR_ADMIN_ID,
                Instant.parse("2026-08-21T09:05:00Z"));

        JsonNode detail =
                detail(login("technician@northstar.example").session(), NORTHSTAR_ALERT_ID);

        assertThat(detail.fieldNames())
                .toIterable()
                .containsExactly(
                        "id",
                        "status",
                        "occurrenceCount",
                        "firstOccurredAt",
                        "lastOccurredAt",
                        "cooldownUntil",
                        "createdAt",
                        "updatedAt",
                        "context",
                        "history");
        assertThat(detail.path("id").asText()).isEqualTo(NORTHSTAR_ALERT_ID.toString());
        assertThat(detail.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(detail.path("occurrenceCount").asLong()).isEqualTo(3);
        assertThat(detail.path("firstOccurredAt").asText()).isEqualTo(FIRST_OCCURRED_AT.toString());
        assertThat(detail.path("lastOccurredAt").asText()).isEqualTo(LAST_OCCURRED_AT.toString());
        assertThat(detail.path("cooldownUntil").asText()).isEqualTo(COOLDOWN_UNTIL.toString());
        assertThat(detail.path("createdAt").asText()).isEqualTo(CREATED_AT.toString());
        assertContext(detail.path("context"));
        assertThat(detail.path("history").size()).isEqualTo(2);
        assertHistory(
                detail.path("history").get(0),
                1,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                "2026-08-21T09:05:00Z");
        assertHistory(
                detail.path("history").get(1),
                2,
                AlertStatus.ACKNOWLEDGED,
                AlertStatus.RESOLVED,
                "2026-08-21T09:10:00Z");
        assertNoInternalOrForeignData(detail);
    }

    @Test
    void foreignAndMissingDetailIdsReturnTheSameNonLeakingProblem() throws Exception {
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                7,
                LAST_OCCURRED_AT);
        MockHttpSession session = login("admin@northstar.example").session();
        List<JsonNode> problems = new ArrayList<>();

        for (UUID alertId : List.of(RIVERSIDE_ALERT_ID, MISSING_ALERT_ID)) {
            MvcResult result =
                    mockMvc.perform(
                                    get(alertPath(alertId))
                                            .session(session)
                                            .accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isNotFound())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value(
                                                    "The requested alert does not exist or is not accessible."))
                            .andExpect(content().string(not(containsString("Riverside"))))
                            .andExpect(content().string(not(containsString("PUMP-201"))))
                            .andReturn();
            problems.add(objectMapper.readTree(result.getResponse().getContentAsString()));
        }

        assertEquivalentProblems(problems.get(0), problems.get(1));
    }

    @Test
    void browserSuppliedTenantAndRoleScopeCannotChangeReadsOrCommands() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                7,
                LAST_OCCURRED_AT.plusSeconds(60));
        AuthenticatedSession admin = login("admin@northstar.example");

        MvcResult listResult =
                mockMvc.perform(
                                get(ALERTS_PATH)
                                        .session(admin.session())
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .queryParam("organisation", "riverside-manufacturing")
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Organisation", "riverside-manufacturing")
                                        .header("X-Role", "VIEWER")
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertThat(list.path("alerts").size()).isOne();
        assertThat(list.path("alerts").get(0).path("id").asText())
                .isEqualTo(NORTHSTAR_ALERT_ID.toString());

        MvcResult command =
                mockMvc.perform(
                                post(alertPath(NORTHSTAR_ALERT_ID) + "/acknowledge")
                                        .session(admin.session())
                                        .header(admin.csrfHeaderName(), admin.csrfToken())
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Role", "VIEWER")
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                        .andReturn();

        JsonNode response = objectMapper.readTree(command.getResponse().getContentAsString());
        assertThat(response.path("history").get(0).path("actor").path("id").asText())
                .isEqualTo(NORTHSTAR_ADMIN_ID.toString());
        assertThat(readAlertStatus(NORTHSTAR_ALERT_ID)).isEqualTo("ACKNOWLEDGED");
        assertThat(readAlertStatus(RIVERSIDE_ALERT_ID)).isEqualTo("OPEN");
    }

    @ParameterizedTest(name = "invalid alert limit: {0}")
    @MethodSource("invalidLimits")
    void invalidLimitsReturnOneStableNonReflectingProblem(String description, String limit)
            throws Exception {
        mockMvc.perform(
                        get(ALERTS_PATH)
                                .session(login("viewer@northstar.example").session())
                                .queryParam("limit", limit)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_ALERT_QUERY"))
                .andExpect(jsonPath("$.title").value("Invalid alert query"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Provide a valid alert result limit from 1 to 100."))
                .andExpect(content().string(not(containsString(description))));
    }

    @Test
    void anonymousReadsAreDeniedBeforeAnyAlertIsResolved() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);

        for (MockHttpServletRequestBuilder request :
                List.of(get(ALERTS_PATH), get(alertPath(NORTHSTAR_ALERT_ID)))) {
            mockMvc.perform(request.accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(content().string(not(containsString("Boiler Feed Pump"))))
                    .andExpect(content().string(not(containsString("PUMP-101"))));
        }
    }

    @Test
    void onlyOperationsAdminWithCsrfMayIssueAlertCommands() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            AuthenticatedSession denied = login(email);
            mockMvc.perform(command(denied, NORTHSTAR_ALERT_ID, "acknowledge"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        CsrfExchange anonymous = csrf(null);
        mockMvc.perform(
                        post(alertPath(NORTHSTAR_ALERT_ID) + "/acknowledge")
                                .session(anonymous.session())
                                .header(anonymous.headerName(), anonymous.token())
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        AuthenticatedSession admin = login("admin@northstar.example");
        mockMvc.perform(
                        post(alertPath(NORTHSTAR_ALERT_ID) + "/acknowledge")
                                .session(admin.session())
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mockMvc.perform(
                        post(alertPath(NORTHSTAR_ALERT_ID) + "/acknowledge")
                                .session(admin.session())
                                .header(admin.csrfHeaderName(), "invalid-token")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertAlertStateAndHistory(NORTHSTAR_ALERT_ID, AlertStatus.OPEN, 0);
        assertThat(alertAuditRows()).isEmpty();
    }

    @Test
    void nonAdminCommandsAreDeniedBeforeForeignOrMissingAlertLookup() throws Exception {
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                1,
                LAST_OCCURRED_AT);
        List<JsonNode> problems = new ArrayList<>();

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            AuthenticatedSession denied = login(email);
            for (String requestedCommand : List.of("acknowledge", "resolve")) {
                for (UUID alertId : List.of(RIVERSIDE_ALERT_ID, MISSING_ALERT_ID)) {
                    MvcResult result =
                            mockMvc.perform(command(denied, alertId, requestedCommand))
                                    .andExpect(status().isForbidden())
                                    .andExpect(header().string("Cache-Control", "no-store"))
                                    .andExpect(
                                            content()
                                                    .contentTypeCompatibleWith(
                                                            MediaType.APPLICATION_PROBLEM_JSON))
                                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                                    .andReturn();
                    problems.add(objectMapper.readTree(result.getResponse().getContentAsString()));
                }
            }
        }

        for (JsonNode problem : problems.subList(1, problems.size())) {
            assertEquivalentProblems(problems.get(0), problem);
        }
        assertAlertStateAndHistory(RIVERSIDE_ALERT_ID, AlertStatus.OPEN, 0);
        assertThat(alertAuditRows()).isEmpty();
    }

    @Test
    @DisplayName("AUD-01: alert commands audit the trusted actor and server correlation ID")
    void operationsAdminAcknowledgesThenResolvesWithOneActorHistoryForEachCommand()
            throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        AuthenticatedSession admin = login("admin@northstar.example");

        MvcResult acknowledgedResult =
                mockMvc.perform(
                                command(admin, NORTHSTAR_ALERT_ID, "acknowledge")
                                        .header("X-User-ID", RIVERSIDE_ADMIN_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Correlation-ID", "browser-controlled"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                        .andExpect(jsonPath("$.history.length()").value(1))
                        .andReturn();
        JsonNode acknowledged =
                objectMapper.readTree(acknowledgedResult.getResponse().getContentAsString());
        assertHistory(
                acknowledged.path("history").get(0),
                1,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                null);
        assertThat(readAlertUpdatedAt(NORTHSTAR_ALERT_ID))
                .isEqualTo(
                        Instant.parse(
                                acknowledged
                                        .path("history")
                                        .get(0)
                                        .path("transitionedAt")
                                        .asText()));
        assertAlertAudit(acknowledgedResult, "ALERT_ACKNOWLEDGED");
        assertThat(alertAuditRows()).hasSize(1);

        MvcResult resolvedResult =
                mockMvc.perform(
                                command(admin, NORTHSTAR_ALERT_ID, "resolve")
                                        .header("X-User-ID", RIVERSIDE_ADMIN_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Correlation-ID", "browser-controlled"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.status").value("RESOLVED"))
                        .andExpect(jsonPath("$.history.length()").value(2))
                        .andReturn();
        JsonNode resolved =
                objectMapper.readTree(resolvedResult.getResponse().getContentAsString());
        assertHistory(
                resolved.path("history").get(0),
                1,
                AlertStatus.OPEN,
                AlertStatus.ACKNOWLEDGED,
                acknowledged.path("history").get(0).path("transitionedAt").asText());
        assertHistory(
                resolved.path("history").get(1),
                2,
                AlertStatus.ACKNOWLEDGED,
                AlertStatus.RESOLVED,
                null);
        assertAlertStateAndHistory(NORTHSTAR_ALERT_ID, AlertStatus.RESOLVED, 2);
        assertAlertAudit(resolvedResult, "ALERT_RESOLVED");
        assertThat(alertAuditRows()).hasSize(2);
    }

    @Test
    void historyInsertFailureRollsBackTheConditionalStateUpdate() {
        insertNorthstarAlert(AlertStatus.OPEN);
        Instant originalUpdatedAt = readAlertUpdatedAt(NORTHSTAR_ALERT_ID);

        assertThatThrownBy(
                        () ->
                                commandService.acknowledge(
                                        NORTHSTAR_ID,
                                        RIVERSIDE_ADMIN_ID,
                                        NORTHSTAR_ALERT_ID,
                                        UUID.randomUUID().toString()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertUnchanged(NORTHSTAR_ALERT_ID, AlertStatus.OPEN, 0, originalUpdatedAt);
        assertThat(alertAuditRows()).isEmpty();
    }

    @Test
    @DisplayName("AUD-01: an audit insert failure rolls back alert state and immutable history")
    void auditInsertFailuresRollBackAlertStateAndHistory() {
        insertNorthstarAlert(AlertStatus.OPEN);

        for (String action : List.of("acknowledge", "resolve")) {
            AlertStatus beforeStatus = AlertStatus.valueOf(readAlertStatus(NORTHSTAR_ALERT_ID));
            Instant beforeUpdatedAt = readAlertUpdatedAt(NORTHSTAR_ALERT_ID);
            int beforeHistory = readHistoryCount(NORTHSTAR_ALERT_ID);
            List<String> beforeAudit = alertAuditRows();
            jdbcClient
                    .sql(
                            "ALTER TABLE audit_event ADD CONSTRAINT ck_alert_test_audit CHECK (subject_alert_id IS NULL) NOT VALID")
                    .update();
            try {
                assertThatThrownBy(
                                () -> {
                                    if ("acknowledge".equals(action)) {
                                        commandService.acknowledge(
                                                NORTHSTAR_ID,
                                                NORTHSTAR_ADMIN_ID,
                                                NORTHSTAR_ALERT_ID,
                                                UUID.randomUUID().toString());
                                    } else {
                                        commandService.resolve(
                                                NORTHSTAR_ID,
                                                NORTHSTAR_ADMIN_ID,
                                                NORTHSTAR_ALERT_ID,
                                                UUID.randomUUID().toString());
                                    }
                                })
                        .isInstanceOf(DataIntegrityViolationException.class);
                assertUnchanged(NORTHSTAR_ALERT_ID, beforeStatus, beforeHistory, beforeUpdatedAt);
                assertThat(alertAuditRows()).isEqualTo(beforeAudit);
            } finally {
                jdbcClient
                        .sql("ALTER TABLE audit_event DROP CONSTRAINT ck_alert_test_audit")
                        .update();
            }
            if ("acknowledge".equals(action)) {
                commandService.acknowledge(
                        NORTHSTAR_ID,
                        NORTHSTAR_ADMIN_ID,
                        NORTHSTAR_ALERT_ID,
                        UUID.randomUUID().toString());
            }
        }
    }

    @Test
    void skippedAndRepeatedTransitionsReturn409WithoutChangingAlertOrHistory() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        AuthenticatedSession admin = login("admin@northstar.example");
        Instant originalUpdatedAt = readAlertUpdatedAt(NORTHSTAR_ALERT_ID);

        assertConflict(command(admin, NORTHSTAR_ALERT_ID, "resolve"));
        assertUnchanged(NORTHSTAR_ALERT_ID, AlertStatus.OPEN, 0, originalUpdatedAt);

        mockMvc.perform(command(admin, NORTHSTAR_ALERT_ID, "acknowledge"))
                .andExpect(status().isOk());
        Instant acknowledgedAt = readAlertUpdatedAt(NORTHSTAR_ALERT_ID);
        assertConflict(command(admin, NORTHSTAR_ALERT_ID, "acknowledge"));
        assertUnchanged(NORTHSTAR_ALERT_ID, AlertStatus.ACKNOWLEDGED, 1, acknowledgedAt);

        mockMvc.perform(command(admin, NORTHSTAR_ALERT_ID, "resolve")).andExpect(status().isOk());
        Instant resolvedAt = readAlertUpdatedAt(NORTHSTAR_ALERT_ID);
        assertConflict(command(admin, NORTHSTAR_ALERT_ID, "resolve"));
        assertUnchanged(NORTHSTAR_ALERT_ID, AlertStatus.RESOLVED, 2, resolvedAt);
        assertConflict(command(admin, NORTHSTAR_ALERT_ID, "acknowledge"));
        assertUnchanged(NORTHSTAR_ALERT_ID, AlertStatus.RESOLVED, 2, resolvedAt);
    }

    @Test
    void foreignAndMissingCommandsReturnTheSame404AndAppendNothing() throws Exception {
        insertAlert(
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_ID,
                RIVERSIDE_RULE_ID,
                RIVERSIDE_FINGERPRINT,
                AlertStatus.OPEN,
                1,
                LAST_OCCURRED_AT);
        AuthenticatedSession northstarAdmin = login("admin@northstar.example");
        List<JsonNode> problems = new ArrayList<>();

        for (UUID alertId : List.of(RIVERSIDE_ALERT_ID, MISSING_ALERT_ID)) {
            MvcResult result =
                    mockMvc.perform(command(northstarAdmin, alertId, "acknowledge"))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"))
                            .andReturn();
            problems.add(objectMapper.readTree(result.getResponse().getContentAsString()));
        }

        assertEquivalentProblems(problems.get(0), problems.get(1));
        assertAlertStateAndHistory(RIVERSIDE_ALERT_ID, AlertStatus.OPEN, 0);
        assertThat(alertAuditRows()).isEmpty();
    }

    @Test
    void concurrentDuplicateAcknowledgeHasOneSuccessOneConflictAndOneHistory() throws Exception {
        insertNorthstarAlert(AlertStatus.OPEN);
        AuthenticatedSession firstAdmin = login("admin@northstar.example");
        AuthenticatedSession secondAdmin = login("admin@northstar.example");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<MvcResult> first =
                    executor.submit(
                            () -> concurrentCommand(firstAdmin, ready, start, "acknowledge"));
            Future<MvcResult> second =
                    executor.submit(
                            () -> concurrentCommand(secondAdmin, ready, start, "acknowledge"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            List<MvcResult> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(
                            results.stream()
                                    .map(result -> result.getResponse().getStatus())
                                    .sorted()
                                    .toList())
                    .containsExactly(200, 409);
            JsonNode success = payloadWithStatus(results, 200);
            JsonNode conflict = payloadWithStatus(results, 409);
            assertThat(success.path("status").asText()).isEqualTo("ACKNOWLEDGED");
            assertThat(success.path("history").size()).isOne();
            assertThat(conflict.path("code").asText()).isEqualTo("ALERT_STATE_CONFLICT");
            assertAlertAudit(
                    results.stream()
                            .filter(result -> result.getResponse().getStatus() == 200)
                            .findFirst()
                            .orElseThrow(),
                    "ALERT_ACKNOWLEDGED");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertAlertStateAndHistory(NORTHSTAR_ALERT_ID, AlertStatus.ACKNOWLEDGED, 1);
        assertThat(alertAuditRows()).hasSize(1);
    }

    private JsonNode listAlerts(MockHttpSession session, String limit) throws Exception {
        MockHttpServletRequestBuilder request =
                get(ALERTS_PATH).session(session).accept(MediaType.APPLICATION_JSON);
        if (limit != null) {
            request.queryParam("limit", limit);
        }
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult openStream(MockHttpSession session) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(ALERT_STREAM_PATH)
                                        .session(session)
                                        .accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(request().asyncStarted())
                        .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("event:ready\ndata:{}\n\n");
        return result;
    }

    private void awaitStreamContains(MvcResult result, String expected) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (result.getResponse().getContentAsString().contains(expected)) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(result.getResponse().getContentAsString()).contains(expected);
    }

    private JsonNode detail(MockHttpSession session, UUID alertId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(alertPath(alertId))
                                        .session(session)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder command(
            AuthenticatedSession authenticated, UUID alertId, String command) {
        return post(alertPath(alertId) + "/" + command)
                .session(authenticated.session())
                .header(authenticated.csrfHeaderName(), authenticated.csrfToken())
                .accept(MediaType.APPLICATION_JSON);
    }

    private MvcResult concurrentCommand(
            AuthenticatedSession authenticated,
            CountDownLatch ready,
            CountDownLatch start,
            String requestedCommand)
            throws Exception {
        ready.countDown();
        await(start);
        return mockMvc.perform(command(authenticated, NORTHSTAR_ALERT_ID, requestedCommand))
                .andReturn();
    }

    private void assertConflict(MockHttpServletRequestBuilder command) throws Exception {
        List<String> beforeAudit = alertAuditRows();
        mockMvc.perform(command)
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ALERT_STATE_CONFLICT"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("The alert cannot transition from its current state."));
        assertThat(alertAuditRows()).isEqualTo(beforeAudit);
    }

    private void assertAlertAudit(MvcResult result, String action) {
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-ID"));
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS('|', organisation_id, actor_user_id,
                                            action, subject_alert_id, correlation_id)
                                        FROM audit_event
                                        WHERE subject_alert_id = :alertId AND action = :action
                                        """)
                                .param("alertId", NORTHSTAR_ALERT_ID)
                                .param("action", action)
                                .query(String.class)
                                .single())
                .isEqualTo(
                        NORTHSTAR_ID
                                + "|"
                                + NORTHSTAR_ADMIN_ID
                                + "|"
                                + action
                                + "|"
                                + NORTHSTAR_ALERT_ID
                                + "|"
                                + correlationId);
    }

    private List<String> alertAuditRows() {
        return jdbcClient
                .sql(
                        "SELECT row_to_json(audit_event)::text FROM audit_event WHERE subject_alert_id IS NOT NULL ORDER BY id")
                .query(String.class)
                .list();
    }

    private void assertContext(JsonNode context) {
        assertThat(context.fieldNames())
                .toIterable()
                .containsExactly("asset", "sensor", "thresholdRule");
        assertThat(context.path("asset").fieldNames())
                .toIterable()
                .containsExactly("id", "assetCode", "name");
        assertThat(context.path("asset").path("id").asText())
                .isEqualTo(NORTHSTAR_ASSET_ID.toString());
        assertThat(context.path("asset").path("assetCode").asText()).isEqualTo("PUMP-101");
        assertThat(context.path("asset").path("name").asText()).isEqualTo("Boiler Feed Pump");
        assertThat(context.path("sensor").fieldNames())
                .toIterable()
                .containsExactly("id", "sensorKey", "name", "measurementType", "unit");
        assertThat(context.path("sensor").path("id").asText())
                .isEqualTo(NORTHSTAR_SENSOR_ID.toString());
        assertThat(context.path("sensor").path("sensorKey").asText()).isEqualTo("PUMP-101-TEMP");
        assertThat(context.path("sensor").path("name").asText()).isEqualTo("Bearing Temperature");
        assertThat(context.path("sensor").path("measurementType").asText())
                .isEqualTo("TEMPERATURE");
        assertThat(context.path("sensor").path("unit").asText()).isEqualTo("CELSIUS");
        assertThat(context.path("thresholdRule").fieldNames())
                .toIterable()
                .containsExactly(
                        "id",
                        "ruleCode",
                        "name",
                        "comparison",
                        "thresholdValue",
                        "cooldownSeconds");
        assertThat(context.path("thresholdRule").path("id").asText())
                .isEqualTo(NORTHSTAR_RULE_ID.toString());
        assertThat(context.path("thresholdRule").path("ruleCode").asText())
                .isEqualTo("PUMP-101-HIGH-TEMP");
        assertThat(context.path("thresholdRule").path("name").asText())
                .isEqualTo("High bearing temperature");
        assertThat(context.path("thresholdRule").path("comparison").asText())
                .isEqualTo("GREATER_THAN_OR_EQUAL_TO");
        assertThat(context.path("thresholdRule").path("thresholdValue").decimalValue())
                .isEqualByComparingTo("80.000000");
        assertThat(context.path("thresholdRule").path("cooldownSeconds").asInt()).isEqualTo(300);
    }

    private void assertHistory(
            JsonNode history,
            int sequence,
            AlertStatus from,
            AlertStatus to,
            String transitionedAt) {
        assertThat(history.fieldNames())
                .toIterable()
                .containsExactly(
                        "sequenceNumber", "fromStatus", "toStatus", "actor", "transitionedAt");
        assertThat(history.path("sequenceNumber").asInt()).isEqualTo(sequence);
        assertThat(history.path("fromStatus").asText()).isEqualTo(from.name());
        assertThat(history.path("toStatus").asText()).isEqualTo(to.name());
        assertThat(history.path("actor").fieldNames())
                .toIterable()
                .containsExactly("id", "displayName");
        assertThat(history.path("actor").path("id").asText())
                .isEqualTo(NORTHSTAR_ADMIN_ID.toString());
        assertThat(history.path("actor").path("displayName").asText()).isEqualTo("Nora Admin");
        assertThat(history.path("transitionedAt").asText()).isNotBlank();
        if (transitionedAt != null) {
            assertThat(history.path("transitionedAt").asText()).isEqualTo(transitionedAt);
        }
    }

    private void assertNoInternalOrForeignData(JsonNode payload) {
        assertThat(payload.toString())
                .doesNotContain("organisationId")
                .doesNotContain("fingerprint")
                .doesNotContain(NORTHSTAR_FINGERPRINT)
                .doesNotContain(RIVERSIDE_ALERT_ID.toString())
                .doesNotContain("Riverside")
                .doesNotContain("PUMP-201");
    }

    private void assertEquivalentProblems(JsonNode first, JsonNode second) {
        for (String field : List.of("status", "code", "title", "detail")) {
            assertThat(first.path(field)).isEqualTo(second.path(field));
        }
    }

    private void assertAlertStateAndHistory(
            UUID alertId, AlertStatus expectedStatus, int expectedHistory) {
        assertThat(readAlertStatus(alertId)).isEqualTo(expectedStatus.name());
        assertThat(readHistoryCount(alertId)).isEqualTo(expectedHistory);
    }

    private void assertUnchanged(
            UUID alertId,
            AlertStatus expectedStatus,
            int expectedHistory,
            Instant expectedUpdatedAt) {
        assertAlertStateAndHistory(alertId, expectedStatus, expectedHistory);
        assertThat(readAlertUpdatedAt(alertId)).isEqualTo(expectedUpdatedAt);
    }

    private void insertNorthstarAlert(AlertStatus status) {
        insertAlert(
                NORTHSTAR_ALERT_ID,
                NORTHSTAR_ID,
                NORTHSTAR_RULE_ID,
                NORTHSTAR_FINGERPRINT,
                status,
                3,
                LAST_OCCURRED_AT);
    }

    private void insertAlert(
            UUID id,
            UUID organisationId,
            UUID ruleId,
            String fingerprint,
            AlertStatus status,
            long occurrenceCount,
            Instant lastOccurredAt) {
        Instant firstOccurredAt =
                lastOccurredAt.isBefore(FIRST_OCCURRED_AT) ? lastOccurredAt : FIRST_OCCURRED_AT;
        Instant cooldownUntil = lastOccurredAt.plusSeconds(300);
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            status,
                            occurrence_count,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            :ruleId,
                            :fingerprint,
                            :status,
                            :occurrenceCount,
                            :firstOccurredAt,
                            :lastOccurredAt,
                            :cooldownUntil,
                            :createdAt,
                            :createdAt
                        )
                        """)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", fingerprint)
                .param("status", status.name())
                .param("occurrenceCount", occurrenceCount)
                .param("firstOccurredAt", firstOccurredAt.atOffset(ZoneOffset.UTC))
                .param("lastOccurredAt", lastOccurredAt.atOffset(ZoneOffset.UTC))
                .param("cooldownUntil", cooldownUntil.atOffset(ZoneOffset.UTC))
                .param("createdAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertResolvedAlertRange(int count) {
        for (int index = 1; index <= count; index++) {
            Instant lastOccurredAt = FIRST_OCCURRED_AT.plusSeconds(index >= 100 ? 100L : index);
            insertAlert(
                    generatedAlertId(index),
                    NORTHSTAR_ID,
                    NORTHSTAR_RULE_ID,
                    NORTHSTAR_FINGERPRINT,
                    AlertStatus.RESOLVED,
                    index,
                    lastOccurredAt);
        }
    }

    private void insertHistory(
            UUID organisationId,
            UUID alertId,
            int sequence,
            AlertStatus from,
            AlertStatus to,
            UUID actorId,
            Instant transitionedAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert_status_history (
                            organisation_id,
                            alert_id,
                            sequence_number,
                            from_status,
                            to_status,
                            actor_user_id,
                            transitioned_at
                        )
                        VALUES (
                            :organisationId,
                            :alertId,
                            :sequence,
                            :fromStatus,
                            :toStatus,
                            :actorId,
                            :transitionedAt
                        )
                        """)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("sequence", sequence)
                .param("fromStatus", from.name())
                .param("toStatus", to.name())
                .param("actorId", actorId)
                .param("transitionedAt", transitionedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private String readAlertStatus(UUID alertId) {
        return jdbcClient
                .sql("SELECT status FROM alert WHERE id = :alertId")
                .param("alertId", alertId)
                .query(String.class)
                .single();
    }

    private Instant readAlertUpdatedAt(UUID alertId) {
        return jdbcClient
                .sql("SELECT updated_at FROM alert WHERE id = :alertId")
                .param("alertId", alertId)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private int readHistoryCount(UUID alertId) {
        return jdbcClient
                .sql(
                        """
                        SELECT COUNT(*)::integer
                        FROM alert_status_history
                        WHERE alert_id = :alertId
                        """)
                .param("alertId", alertId)
                .query(Integer.class)
                .single();
    }

    private JsonNode payloadWithStatus(List<MvcResult> results, int status) throws Exception {
        MvcResult matching =
                results.stream()
                        .filter(result -> result.getResponse().getStatus() == status)
                        .findFirst()
                        .orElseThrow();
        return objectMapper.readTree(matching.getResponse().getContentAsString());
    }

    private AuthenticatedSession login(String email) throws Exception {
        CsrfExchange csrf = csrf(null);
        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                Map.of(
                                                        "email", email,
                                                        "password", DEMO_PASSWORD))))
                .andExpect(status().isOk());
        CsrfExchange authenticatedCsrf = csrf(csrf.session());
        return new AuthenticatedSession(
                csrf.session(), authenticatedCsrf.headerName(), authenticatedCsrf.token());
    }

    private CsrfExchange csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get(CSRF_PATH).accept(MediaType.APPLICATION_JSON);
        if (session != null) {
            request.session(session);
        }
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        MockHttpSession resolvedSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(resolvedSession).isNotNull();
        return new CsrfExchange(
                resolvedSession,
                payload.path("headerName").asText(),
                payload.path("token").asText());
    }

    private String alertPath(UUID alertId) {
        return ALERTS_PATH + "/" + alertId;
    }

    private static UUID generatedAlertId(int index) {
        return UUID.fromString("82000000-0000-0000-0000-%012d".formatted(index));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent alert commands");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Stream<String> seededNorthstarRoles() {
        return Stream.of(
                "admin@northstar.example",
                "technician@northstar.example",
                "viewer@northstar.example");
    }

    private static Stream<Arguments> invalidLimits() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("zero", "0"),
                Arguments.of("negative", "-1"),
                Arguments.of("above maximum", "101"),
                Arguments.of("non-numeric", "many"),
                Arguments.of("leading zero", "01"),
                Arguments.of("explicit plus", "+1"),
                Arguments.of("surrounding whitespace", " 1 "));
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}

    private record AuthenticatedSession(
            MockHttpSession session, String csrfHeaderName, String csrfToken) {}
}
