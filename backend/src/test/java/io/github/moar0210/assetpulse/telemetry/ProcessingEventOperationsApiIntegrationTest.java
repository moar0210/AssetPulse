package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
class ProcessingEventOperationsApiIntegrationTest {

    private static final String PROCESSING_EVENTS_PATH = "/api/v1/processing-events";
    private static final String DEAD_EVENTS_PATH = PROCESSING_EVENTS_PATH + "/dead";
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
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_DEAD_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000016");
    private static final UUID NORTHSTAR_DEAD_BATCH_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000016");
    private static final UUID NORTHSTAR_PENDING_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000017");
    private static final UUID NORTHSTAR_PENDING_BATCH_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000017");
    private static final UUID NORTHSTAR_PROCESSING_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000019");
    private static final UUID NORTHSTAR_PROCESSING_BATCH_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000019");
    private static final UUID NORTHSTAR_COMPLETED_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000020");
    private static final UUID NORTHSTAR_COMPLETED_BATCH_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000020");
    private static final UUID RIVERSIDE_DEAD_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000018");
    private static final UUID RIVERSIDE_DEAD_BATCH_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000018");
    private static final UUID MISSING_EVENT_ID =
            UUID.fromString("99999999-0000-0000-0000-000000000016");
    private static final Instant CREATED_AT = Instant.parse("2026-08-24T08:00:00Z");
    private static final Instant DEAD_AT = Instant.parse("2026-08-24T10:00:00Z");
    private static final String SAFE_ERROR_CODE = "PROCESSING_FAILED";
    private static final String SAFE_ERROR_MESSAGE =
            "Processing failed; another attempt may be scheduled.";

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
    @Autowired private TelemetryProcessingLifecycleService lifecycleService;
    @Autowired private TelemetryProcessingExecutionService executionService;
    @Autowired private ProcessingEventOperationsService operationsService;

    @BeforeEach
    void clearTelemetry() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void defaultAndMaximumListsAreBoundedTenantScopedDeterministicAndSafe() throws Exception {
        for (int index = 1; index <= 101; index++) {
            Instant deadAt = DEAD_AT.plusSeconds(index >= 100 ? 100L : index);
            insertDeadEvent(
                    generatedEventId(index),
                    generatedBatchId(index),
                    NORTHSTAR_ID,
                    CREATED_AT.plusSeconds(index),
                    deadAt);
        }
        insertDeadEvent(
                RIVERSIDE_DEAD_EVENT_ID,
                RIVERSIDE_DEAD_BATCH_ID,
                RIVERSIDE_ID,
                CREATED_AT,
                DEAD_AT.plusSeconds(10_000));
        AuthenticatedSession admin = login("admin@northstar.example");

        JsonNode defaultPayload = listDead(admin.session(), null);

        assertThat(defaultPayload.fieldNames()).toIterable().containsExactly("events", "limit");
        assertThat(defaultPayload.path("limit").asInt()).isEqualTo(50);
        assertThat(defaultPayload.path("events").size()).isEqualTo(50);
        assertThat(defaultPayload.path("events").get(0).path("id").asText())
                .isEqualTo(generatedEventId(100).toString());
        assertThat(defaultPayload.path("events").get(1).path("id").asText())
                .isEqualTo(generatedEventId(101).toString());
        assertThat(defaultPayload.path("events").get(49).path("id").asText())
                .isEqualTo(generatedEventId(52).toString());
        assertExactSafeEvent(defaultPayload.path("events").get(0), 100);
        assertSafePayload(defaultPayload);

        JsonNode maximumPayload = listDead(admin.session(), "100");

        assertThat(maximumPayload.path("limit").asInt()).isEqualTo(100);
        assertThat(maximumPayload.path("events").size()).isEqualTo(100);
        assertThat(maximumPayload.path("events").get(99).path("id").asText())
                .isEqualTo(generatedEventId(2).toString());
        assertThat(maximumPayload.toString())
                .doesNotContain(generatedEventId(1).toString())
                .doesNotContain(RIVERSIDE_DEAD_EVENT_ID.toString());
        assertSafePayload(maximumPayload);
    }

    @Test
    void anOrganisationWithNoDeadEventsReceivesAnExplicitEmptyQueue() throws Exception {
        insertDeadEvent(
                RIVERSIDE_DEAD_EVENT_ID,
                RIVERSIDE_DEAD_BATCH_ID,
                RIVERSIDE_ID,
                CREATED_AT,
                DEAD_AT);

        JsonNode payload = listDead(login("admin@northstar.example").session(), null);

        assertThat(payload.fieldNames()).toIterable().containsExactly("events", "limit");
        assertThat(payload.path("limit").asInt()).isEqualTo(50);
        assertThat(payload.path("events").isArray()).isTrue();
        assertThat(payload.path("events")).isEmpty();
    }

    @Test
    void invalidLimitsReturnOneStableNonReflectingProblem() throws Exception {
        MockHttpSession session = login("admin@northstar.example").session();

        for (String limit : List.of("", "0", "-1", "101", "many", "01", "+1", " 1 ")) {
            mockMvc.perform(
                            get(DEAD_EVENTS_PATH)
                                    .session(session)
                                    .queryParam("limit", limit)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_PROCESSING_EVENT_QUERY"))
                    .andExpect(jsonPath("$.title").value("Invalid processing event query"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Provide a valid processing event result limit from 1 to 100."));
        }
    }

    @Test
    void onlyOperationsAdminsCanReadAndBrowserScopeCannotEscalateOrChangeTenant() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        insertDeadEvent(
                RIVERSIDE_DEAD_EVENT_ID,
                RIVERSIDE_DEAD_BATCH_ID,
                RIVERSIDE_ID,
                CREATED_AT,
                DEAD_AT.plusSeconds(1));

        mockMvc.perform(get(DEAD_EVENTS_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            MockHttpSession deniedSession = login(email).session();
            mockMvc.perform(
                            get(DEAD_EVENTS_PATH)
                                    .session(deniedSession)
                                    .header("X-Role", "OPERATIONS_ADMIN")
                                    .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                    .queryParam("organisationId", RIVERSIDE_ID.toString())
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

            mockMvc.perform(head(DEAD_EVENTS_PATH).session(deniedSession))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        MvcResult result =
                mockMvc.perform(
                                get(DEAD_EVENTS_PATH)
                                        .session(login("admin@northstar.example").session())
                                        .header("X-Role", "VIEWER")
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("events").size()).isOne();
        assertThat(payload.path("events").get(0).path("id").asText())
                .isEqualTo(NORTHSTAR_DEAD_EVENT_ID.toString());
        assertSafePayload(payload);
    }

    @Test
    void retryRequiresAnOperationsAdminSessionAndValidCsrfBeforeLookup() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            AuthenticatedSession denied = login(email);
            for (UUID eventId : List.of(NORTHSTAR_DEAD_EVENT_ID, MISSING_EVENT_ID)) {
                mockMvc.perform(retry(denied, eventId))
                        .andExpect(status().isForbidden())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
        }

        CsrfExchange anonymous = csrf(null);
        mockMvc.perform(
                        post(retryPath(NORTHSTAR_DEAD_EVENT_ID))
                                .session(anonymous.session())
                                .header(anonymous.headerName(), anonymous.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        AuthenticatedSession admin = login("admin@northstar.example");
        mockMvc.perform(post(retryPath(NORTHSTAR_DEAD_EVENT_ID)).session(admin.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mockMvc.perform(
                        post(retryPath(NORTHSTAR_DEAD_EVENT_ID))
                                .session(admin.session())
                                .header(admin.csrfHeaderName(), "invalid-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertThat(readEvent(NORTHSTAR_DEAD_EVENT_ID).status()).isEqualTo("DEAD");
        assertThat(retryAuditRows()).isEmpty();
    }

    @Test
    void missingAndForeignRetriesReturnTheSameNonLeakingNotFoundProblem() throws Exception {
        insertDeadEvent(
                RIVERSIDE_DEAD_EVENT_ID,
                RIVERSIDE_DEAD_BATCH_ID,
                RIVERSIDE_ID,
                CREATED_AT,
                DEAD_AT);
        AuthenticatedSession admin = login("admin@northstar.example");
        List<JsonNode> problems = new ArrayList<>();

        for (UUID eventId : List.of(RIVERSIDE_DEAD_EVENT_ID, MISSING_EVENT_ID)) {
            MvcResult result =
                    mockMvc.perform(
                                    retry(admin, eventId)
                                            .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                            .header("X-Role", "OPERATIONS_ADMIN")
                                            .queryParam("organisationId", RIVERSIDE_ID.toString()))
                            .andExpect(status().isNotFound())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value("PROCESSING_EVENT_NOT_FOUND"))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value(
                                                    "The requested processing event does not exist or is not accessible."))
                            .andExpect(
                                    content()
                                            .string(not(containsString("Riverside Manufacturing"))))
                            .andReturn();
            problems.add(objectMapper.readTree(result.getResponse().getContentAsString()));
        }

        assertEquivalentProblems(problems.get(0), problems.get(1));
        assertThat(readEvent(RIVERSIDE_DEAD_EVENT_ID).status()).isEqualTo("DEAD");
        assertThat(retryAuditRows()).isEmpty();
    }

    @Test
    @DisplayName("AUD-01: manual retry audits only the admitted request with trusted attribution")
    void retryRearmsOnlyTheExistingDeadRowAndRepeatedOrNonDeadRetriesConflict() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        insertPendingEvent(
                NORTHSTAR_PENDING_EVENT_ID,
                NORTHSTAR_PENDING_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT.plusSeconds(1));
        insertProcessingEvent(
                NORTHSTAR_PROCESSING_EVENT_ID,
                NORTHSTAR_PROCESSING_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT.plusSeconds(2));
        insertCompletedEvent(
                NORTHSTAR_COMPLETED_EVENT_ID,
                NORTHSTAR_COMPLETED_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT.plusSeconds(3));
        insertReading(NORTHSTAR_DEAD_BATCH_ID);
        AuthenticatedSession admin = login("admin@northstar.example");
        TableCounts before = tableCounts();

        MvcResult result =
                mockMvc.perform(
                                retry(admin, NORTHSTAR_DEAD_EVENT_ID)
                                        .header("X-User-ID", RIVERSIDE_ADMIN_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Correlation-ID", "browser-controlled"))
                        .andExpect(status().isNoContent())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().string(""))
                        .andReturn();

        ProcessingRow retried = readEvent(NORTHSTAR_DEAD_EVENT_ID);
        assertFreshPending(retried);
        assertThat(retried.nextAttemptAt()).isEqualTo(retried.updatedAt());
        assertThat(retried.updatedAt()).isAfter(DEAD_AT);
        assertThat(tableCounts()).isEqualTo(before);
        assertRetryAudit(result);
        List<String> admittedAudit = retryAuditRows();
        assertThat(admittedAudit).hasSize(1);

        for (UUID eventId :
                List.of(
                        NORTHSTAR_DEAD_EVENT_ID,
                        NORTHSTAR_PENDING_EVENT_ID,
                        NORTHSTAR_PROCESSING_EVENT_ID,
                        NORTHSTAR_COMPLETED_EVENT_ID)) {
            mockMvc.perform(retry(admin, eventId))
                    .andExpect(status().isConflict())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("PROCESSING_EVENT_STATE_CONFLICT"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "The processing event cannot be retried from its current state."));
        }

        assertThat(readEvent(NORTHSTAR_DEAD_EVENT_ID)).isEqualTo(retried);
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(retryAuditRows()).isEqualTo(admittedAudit);
    }

    @Test
    void concurrentRetriesAdmitExactlyOneWinnerAndAuditOnlyThatRequest() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        insertReading(NORTHSTAR_DEAD_BATCH_ID);
        TableCounts before = tableCounts();
        AuthenticatedSession firstAdmin = login("admin@northstar.example");
        AuthenticatedSession secondAdmin = login("admin@northstar.example");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<MvcResult> first =
                    executor.submit(() -> concurrentRetry(firstAdmin, ready, start));
            Future<MvcResult> second =
                    executor.submit(() -> concurrentRetry(secondAdmin, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            List<MvcResult> results =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(
                            results.stream()
                                    .map(result -> result.getResponse().getStatus())
                                    .sorted()
                                    .toList())
                    .containsExactly(204, 409);
            assertThat(
                            results.stream()
                                    .filter(result -> result.getResponse().getStatus() == 204)
                                    .findFirst()
                                    .orElseThrow()
                                    .getResponse()
                                    .getHeader("Cache-Control"))
                    .isEqualTo("no-store");
            JsonNode conflict = payloadWithStatus(results, 409);
            assertThat(conflict.path("code").asText()).isEqualTo("PROCESSING_EVENT_STATE_CONFLICT");
            assertRetryAudit(
                    results.stream()
                            .filter(result -> result.getResponse().getStatus() == 204)
                            .findFirst()
                            .orElseThrow());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertFreshPending(readEvent(NORTHSTAR_DEAD_EVENT_ID));
        assertThat(tableCounts()).isEqualTo(before);
        assertThat(retryAuditRows()).hasSize(1);
    }

    @Test
    @DisplayName("AUD-01: an audit insert failure leaves the dead processing event unchanged")
    void auditInsertFailureRollsBackEveryProcessingRetryField() {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        ProcessingRow before = readEvent(NORTHSTAR_DEAD_EVENT_ID);
        TableCounts beforeCounts = tableCounts();
        jdbcClient
                .sql(
                        "ALTER TABLE audit_event ADD CONSTRAINT ck_processing_test_audit CHECK (subject_processing_event_id IS NULL) NOT VALID")
                .update();
        try {
            assertThatThrownBy(
                            () ->
                                    operationsService.retryDeadForOrganisation(
                                            NORTHSTAR_ID,
                                            NORTHSTAR_ADMIN_ID,
                                            NORTHSTAR_DEAD_EVENT_ID,
                                            UUID.randomUUID().toString()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(readEvent(NORTHSTAR_DEAD_EVENT_ID)).isEqualTo(before);
            assertThat(tableCounts()).isEqualTo(beforeCounts);
            assertThat(retryAuditRows()).isEmpty();
        } finally {
            jdbcClient
                    .sql("ALTER TABLE audit_event DROP CONSTRAINT ck_processing_test_audit")
                    .update();
        }
    }

    @Test
    void aManuallyRetriedEventCanBeClaimedAndCompleted() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        AuthenticatedSession admin = login("admin@northstar.example");

        mockMvc.perform(retry(admin, NORTHSTAR_DEAD_EVENT_ID)).andExpect(status().isNoContent());
        ProcessingRow pending = readEvent(NORTHSTAR_DEAD_EVENT_ID);
        assertFreshPending(pending);

        TelemetryProcessingClaim claim =
                lifecycleService
                        .claimNext("manual-retry-completion", pending.nextAttemptAt())
                        .orElseThrow();
        assertThat(claim.event().id()).isEqualTo(NORTHSTAR_DEAD_EVENT_ID);
        assertThat(claim.attemptCount()).isOne();
        assertThat(executionService.execute(claim, event -> {})).isTrue();

        ProcessingRow completed = readEvent(NORTHSTAR_DEAD_EVENT_ID);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.attemptCount()).isOne();
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.deadAt()).isNull();
        assertThat(completed.lastErrorCode()).isNull();
        assertThat(completed.lastErrorMessage()).isNull();
    }

    @Test
    void aManualRetryGetsFiveFreshFailedClaimsButNeverASixthAutomaticClaim() throws Exception {
        insertDeadEvent(
                NORTHSTAR_DEAD_EVENT_ID,
                NORTHSTAR_DEAD_BATCH_ID,
                NORTHSTAR_ID,
                CREATED_AT,
                DEAD_AT);
        AuthenticatedSession admin = login("admin@northstar.example");

        mockMvc.perform(retry(admin, NORTHSTAR_DEAD_EVENT_ID)).andExpect(status().isNoContent());
        Instant claimAt = readEvent(NORTHSTAR_DEAD_EVENT_ID).nextAttemptAt();

        for (int attempt = 1; attempt <= 5; attempt++) {
            TelemetryProcessingClaim claim =
                    lifecycleService.claimNext("fresh-cycle-" + attempt, claimAt).orElseThrow();
            assertThat(claim.event().id()).isEqualTo(NORTHSTAR_DEAD_EVENT_ID);
            assertThat(claim.attemptCount()).isEqualTo(attempt);
            Instant failedAt = claimAt.plusSeconds(1);
            TelemetryProcessingFailureDisposition disposition =
                    lifecycleService.recordFailure(claim, failedAt);

            if (attempt < 5) {
                assertThat(disposition)
                        .isEqualTo(TelemetryProcessingFailureDisposition.RETRY_SCHEDULED);
                ProcessingRow scheduled = readEvent(NORTHSTAR_DEAD_EVENT_ID);
                assertThat(scheduled.status()).isEqualTo("PENDING");
                assertThat(scheduled.attemptCount()).isEqualTo(attempt);
                claimAt = scheduled.nextAttemptAt();
            } else {
                assertThat(disposition).isEqualTo(TelemetryProcessingFailureDisposition.DEAD);
            }
        }

        ProcessingRow exhausted = readEvent(NORTHSTAR_DEAD_EVENT_ID);
        assertThat(exhausted.status()).isEqualTo("DEAD");
        assertThat(exhausted.attemptCount()).isEqualTo(5);
        assertThat(exhausted.nextAttemptAt()).isNull();
        assertThat(exhausted.deadAt()).isNotNull();
        assertThat(lifecycleService.claimNext("forbidden-sixth", DEAD_AT.plusSeconds(86_400)))
                .isEmpty();
        assertThat(readEvent(NORTHSTAR_DEAD_EVENT_ID)).isEqualTo(exhausted);
    }

    private JsonNode listDead(MockHttpSession session, String limit) throws Exception {
        MockHttpServletRequestBuilder request =
                get(DEAD_EVENTS_PATH).session(session).accept(MediaType.APPLICATION_JSON);
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

    private MockHttpServletRequestBuilder retry(AuthenticatedSession authenticated, UUID eventId) {
        return post(retryPath(eventId))
                .session(authenticated.session())
                .header(authenticated.csrfHeaderName(), authenticated.csrfToken())
                .accept(MediaType.APPLICATION_JSON);
    }

    private MvcResult concurrentRetry(
            AuthenticatedSession authenticated, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        await(start);
        return mockMvc.perform(retry(authenticated, NORTHSTAR_DEAD_EVENT_ID)).andReturn();
    }

    private void assertExactSafeEvent(JsonNode event, int index) {
        assertThat(event.fieldNames())
                .toIterable()
                .containsExactly(
                        "id",
                        "telemetryBatchId",
                        "eventType",
                        "attemptCount",
                        "createdAt",
                        "deadAt",
                        "updatedAt",
                        "lastErrorCode",
                        "lastErrorMessage");
        assertThat(event.path("id").asText()).isEqualTo(generatedEventId(index).toString());
        assertThat(event.path("telemetryBatchId").asText())
                .isEqualTo(generatedBatchId(index).toString());
        assertThat(event.path("eventType").asText()).isEqualTo("TELEMETRY_BATCH_ACCEPTED");
        assertThat(event.path("attemptCount").asInt()).isEqualTo(5);
        assertThat(event.path("createdAt").asText())
                .isEqualTo(CREATED_AT.plusSeconds(index).toString());
        assertThat(event.path("deadAt").asText())
                .isEqualTo(DEAD_AT.plusSeconds(index >= 100 ? 100L : index).toString());
        assertThat(event.path("updatedAt")).isEqualTo(event.path("deadAt"));
        assertThat(event.path("lastErrorCode").asText()).isEqualTo(SAFE_ERROR_CODE);
        assertThat(event.path("lastErrorMessage").asText()).isEqualTo(SAFE_ERROR_MESSAGE);
    }

    private void assertSafePayload(JsonNode payload) {
        assertThat(payload.toString())
                .doesNotContain("organisationId")
                .doesNotContain("claimToken")
                .doesNotContain("claimOwner")
                .doesNotContain("leaseExpiresAt")
                .doesNotContain("nextAttemptAt")
                .doesNotContain("completedAt")
                .doesNotContain("requestFingerprint")
                .doesNotContain("reading")
                .doesNotContain(RIVERSIDE_DEAD_EVENT_ID.toString());
    }

    private void assertFreshPending(ProcessingRow row) {
        assertThat(row.status()).isEqualTo("PENDING");
        assertThat(row.attemptCount()).isZero();
        assertThat(row.nextAttemptAt()).isNotNull();
        assertThat(row.claimToken()).isNull();
        assertThat(row.claimOwner()).isNull();
        assertThat(row.leaseExpiresAt()).isNull();
        assertThat(row.completedAt()).isNull();
        assertThat(row.deadAt()).isNull();
        assertThat(row.lastErrorCode()).isNull();
        assertThat(row.lastErrorMessage()).isNull();
    }

    private void assertEquivalentProblems(JsonNode first, JsonNode second) {
        for (String field : List.of("status", "code", "title", "detail")) {
            assertThat(first.path(field)).isEqualTo(second.path(field));
        }
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

    private void insertDeadEvent(
            UUID eventId, UUID batchId, UUID organisationId, Instant createdAt, Instant deadAt) {
        insertBatch(batchId, organisationId, eventId.toString(), createdAt);
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at,
                            status,
                            attempt_count,
                            next_attempt_at,
                            dead_at,
                            updated_at,
                            last_error_code,
                            last_error_message
                        )
                        VALUES (
                            :eventId,
                            :organisationId,
                            :batchId,
                            'TELEMETRY_BATCH_ACCEPTED',
                            :createdAt,
                            'DEAD',
                            5,
                            NULL,
                            :deadAt,
                            :deadAt,
                            :errorCode,
                            :errorMessage
                        )
                        """)
                .param("eventId", eventId)
                .param("organisationId", organisationId)
                .param("batchId", batchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("deadAt", deadAt.atOffset(ZoneOffset.UTC))
                .param("errorCode", SAFE_ERROR_CODE)
                .param("errorMessage", SAFE_ERROR_MESSAGE)
                .update();
    }

    private void insertPendingEvent(
            UUID eventId, UUID batchId, UUID organisationId, Instant createdAt) {
        insertBatch(batchId, organisationId, eventId.toString(), createdAt);
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at,
                            next_attempt_at,
                            updated_at
                        )
                        VALUES (
                            :eventId,
                            :organisationId,
                            :batchId,
                            'TELEMETRY_BATCH_ACCEPTED',
                            :createdAt,
                            :createdAt,
                            :createdAt
                        )
                        """)
                .param("eventId", eventId)
                .param("organisationId", organisationId)
                .param("batchId", batchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertProcessingEvent(
            UUID eventId, UUID batchId, UUID organisationId, Instant createdAt) {
        insertBatch(batchId, organisationId, eventId.toString(), createdAt);
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at,
                            status,
                            attempt_count,
                            next_attempt_at,
                            claim_token,
                            claim_owner,
                            lease_expires_at,
                            updated_at
                        )
                        VALUES (
                            :eventId,
                            :organisationId,
                            :batchId,
                            'TELEMETRY_BATCH_ACCEPTED',
                            :createdAt,
                            'PROCESSING',
                            2,
                            NULL,
                            :claimToken,
                            'conflict-test-worker',
                            :leaseExpiresAt,
                            :createdAt
                        )
                        """)
                .param("eventId", eventId)
                .param("organisationId", organisationId)
                .param("batchId", batchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("claimToken", UUID.randomUUID())
                .param("leaseExpiresAt", createdAt.plusSeconds(30).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertCompletedEvent(
            UUID eventId, UUID batchId, UUID organisationId, Instant createdAt) {
        insertBatch(batchId, organisationId, eventId.toString(), createdAt);
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at,
                            status,
                            attempt_count,
                            next_attempt_at,
                            completed_at,
                            updated_at
                        )
                        VALUES (
                            :eventId,
                            :organisationId,
                            :batchId,
                            'TELEMETRY_BATCH_ACCEPTED',
                            :createdAt,
                            'COMPLETED',
                            3,
                            NULL,
                            :completedAt,
                            :completedAt
                        )
                        """)
                .param("eventId", eventId)
                .param("organisationId", organisationId)
                .param("batchId", batchId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("completedAt", createdAt.plusSeconds(10).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertBatch(
            UUID batchId, UUID organisationId, String idempotencyKey, Instant acceptedAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_batch (
                            id,
                            organisation_id,
                            idempotency_key,
                            request_fingerprint,
                            reading_count,
                            accepted_at
                        )
                        VALUES (
                            :batchId,
                            :organisationId,
                            :idempotencyKey,
                            :requestFingerprint,
                            1,
                            :acceptedAt
                        )
                        """)
                .param("batchId", batchId)
                .param("organisationId", organisationId)
                .param("idempotencyKey", idempotencyKey)
                .param("requestFingerprint", "0".repeat(64))
                .param("acceptedAt", acceptedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertReading(UUID batchId) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_reading (
                            id,
                            organisation_id,
                            batch_id,
                            sequence_number,
                            sensor_id,
                            value,
                            observed_at
                        )
                        VALUES (
                            :id,
                            :organisationId,
                            :batchId,
                            0,
                            :sensorId,
                            81.500000,
                            :observedAt
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("organisationId", NORTHSTAR_ID)
                .param("batchId", batchId)
                .param("sensorId", NORTHSTAR_SENSOR_ID)
                .param("observedAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();
    }

    private ProcessingRow readEvent(UUID eventId) {
        return jdbcClient
                .sql(
                        """
                        SELECT
                            status,
                            attempt_count,
                            next_attempt_at,
                            claim_token,
                            claim_owner,
                            lease_expires_at,
                            completed_at,
                            dead_at,
                            last_error_code,
                            last_error_message,
                            updated_at
                        FROM telemetry_processing_event
                        WHERE id = :eventId
                        """)
                .param("eventId", eventId)
                .query(ProcessingEventOperationsApiIntegrationTest::mapProcessingRow)
                .single();
    }

    private void assertRetryAudit(MvcResult result) {
        UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-ID"));
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS('|', organisation_id, actor_user_id,
                                            action, subject_processing_event_id, correlation_id)
                                        FROM audit_event
                                        WHERE subject_processing_event_id = :eventId
                                        """)
                                .param("eventId", NORTHSTAR_DEAD_EVENT_ID)
                                .query(String.class)
                                .single())
                .isEqualTo(
                        NORTHSTAR_ID
                                + "|"
                                + NORTHSTAR_ADMIN_ID
                                + "|PROCESSING_EVENT_RETRIED|"
                                + NORTHSTAR_DEAD_EVENT_ID
                                + "|"
                                + correlationId);
    }

    private List<String> retryAuditRows() {
        return jdbcClient
                .sql(
                        "SELECT row_to_json(audit_event)::text FROM audit_event WHERE subject_processing_event_id IS NOT NULL ORDER BY id")
                .query(String.class)
                .list();
    }

    private static ProcessingRow mapProcessingRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ProcessingRow(
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getString("claim_owner"),
                instant(resultSet, "lease_expires_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "dead_at"),
                resultSet.getString("last_error_code"),
                resultSet.getString("last_error_message"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private TableCounts tableCounts() {
        return new TableCounts(
                count("telemetry_processing_event"),
                count("telemetry_batch"),
                count("telemetry_reading"));
    }

    private int count(String table) {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM " + table)
                .query(Integer.class)
                .single();
    }

    private JsonNode payloadWithStatus(List<MvcResult> results, int expectedStatus)
            throws Exception {
        MvcResult matching =
                results.stream()
                        .filter(result -> result.getResponse().getStatus() == expectedStatus)
                        .findFirst()
                        .orElseThrow();
        return objectMapper.readTree(matching.getResponse().getContentAsString());
    }

    private String retryPath(UUID eventId) {
        return PROCESSING_EVENTS_PATH + "/" + eventId + "/retry";
    }

    private static UUID generatedEventId(int index) {
        return UUID.fromString("71000000-0000-0000-0000-%012d".formatted(index));
    }

    private static UUID generatedBatchId(int index) {
        return UUID.fromString("51000000-0000-0000-0000-%012d".formatted(index));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent retries");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}

    private record AuthenticatedSession(
            MockHttpSession session, String csrfHeaderName, String csrfToken) {}

    private record TableCounts(int processingEvents, int batches, int readings) {}

    private record ProcessingRow(
            String status,
            int attemptCount,
            Instant nextAttemptAt,
            UUID claimToken,
            String claimOwner,
            Instant leaseExpiresAt,
            Instant completedAt,
            Instant deadAt,
            String lastErrorCode,
            String lastErrorMessage,
            Instant updatedAt) {}
}
