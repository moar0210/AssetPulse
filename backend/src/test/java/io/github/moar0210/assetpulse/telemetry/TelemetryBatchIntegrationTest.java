package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ASSETPULSE_SESSION_COOKIE_SECURE=false")
@AutoConfigureMockMvc
@Testcontainers
class TelemetryBatchIntegrationTest {

    private static final String TELEMETRY_PATH = "/api/v1/telemetry-batches";
    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";
    private static final String NORTHSTAR_SENSOR = "30000000-0000-0000-0000-000000000001";
    private static final String RIVERSIDE_SENSOR = "30000000-0000-0000-0000-000000000003";

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
    @Autowired private TelemetryBatchService telemetryBatchService;
    @Autowired private TelemetryBatchRepository telemetryBatchRepository;
    @Autowired private TelemetryProcessingEventRepository telemetryProcessingEventRepository;
    @Autowired private TelemetryBatchFingerprint telemetryBatchFingerprint;
    @Autowired private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearTelemetry() {
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void acceptsOneReadingAndReturnsTheOriginalResultForAnExactRetry() throws Exception {
        AuthenticatedSession authenticated = login("admin@northstar.example");
        Map<String, Object> request = request("northstar-run-1", NORTHSTAR_SENSOR, "73.250000");

        MvcResult first =
                accept(authenticated, request)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.idempotencyKey").value("northstar-run-1"))
                        .andExpect(jsonPath("$.readingCount").value(1))
                        .andExpect(jsonPath("$.batchId").isNotEmpty())
                        .andExpect(jsonPath("$.acceptedAt").isNotEmpty())
                        .andReturn();
        MvcResult retry = accept(authenticated, request).andExpect(status().isOk()).andReturn();

        assertThat(retry.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
    }

    @ParameterizedTest
    @CsvSource({
        "73.2500000, 73.25",
        "-73.2500000, -73.25",
        "0.0000000, 0",
        "1.0000000E-6, 0.000001",
        "-1.0000000E-6, -0.000001",
        "1000000000000.0000000, 1000000000000",
        "-1000000000000.0000000, -1000000000000"
    })
    void equivalentDecimalRepresentationsRetainTheOriginalResultInEitherRetryDirection(
            String paddedValue, String canonicalValue) throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");

        assertEquivalentValueRetry(admin, "padded-first", paddedValue, canonicalValue);
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();

        assertEquivalentValueRetry(admin, "canonical-first", canonicalValue, paddedValue);
        assertThat(count("telemetry_batch")).isEqualTo(2);
        assertThat(count("telemetry_reading")).isEqualTo(2);
        assertThat(count("telemetry_processing_event")).isEqualTo(2);
    }

    @Test
    void acceptsOneHundredBoundaryReadingsAndRetainsTheirSequenceOnReplay() throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        List<String> boundaryValues =
                List.of(
                        "-1000000000000.0000000",
                        "-0.0000010",
                        "0.0000000",
                        "0.0000010",
                        "1000000000000.0000000");
        List<Map<String, Object>> readings =
                IntStream.range(0, 100)
                        .mapToObj(
                                index ->
                                        reading(
                                                NORTHSTAR_SENSOR,
                                                boundaryValues.get(index % boundaryValues.size())))
                        .toList();
        Map<String, Object> request =
                Map.of("idempotencyKey", "maximum-batch", "readings", readings);

        String accepted =
                accept(admin, request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.readingCount").value(100))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replayed =
                accept(admin, request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(replayed).isEqualTo(accepted);
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isEqualTo(100);
        assertThat(count("telemetry_processing_event")).isOne();
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT sequence_number FROM telemetry_reading ORDER BY sequence_number")
                                .query(Integer.class)
                                .list())
                .containsExactlyElementsOf(IntStream.range(0, 100).boxed().toList());
        List<BigDecimal> storedValues =
                jdbcClient
                        .sql("SELECT value FROM telemetry_reading ORDER BY sequence_number")
                        .query(BigDecimal.class)
                        .list();
        for (int index = 0; index < storedValues.size(); index++) {
            assertThat(storedValues.get(index))
                    .isEqualByComparingTo(
                            new BigDecimal(boundaryValues.get(index % boundaryValues.size())));
        }
    }

    @Test
    void conflictingReuseReturns409WithoutChangingStoredRows() throws Exception {
        AuthenticatedSession authenticated = login("admin@northstar.example");
        accept(authenticated, request("stable-key", NORTHSTAR_SENSOR, "73.25"))
                .andExpect(status().isOk());

        accept(authenticated, request("stable-key", NORTHSTAR_SENSOR, "74.25"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
    }

    @Test
    void concurrentExactRetriesReturnOneStoredResult() throws Exception {
        UUID organisationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TelemetryBatchRequest request =
                new TelemetryBatchRequest(
                        "concurrent-exact",
                        List.of(
                                new TelemetryBatchRequest.Reading(
                                        UUID.fromString(NORTHSTAR_SENSOR),
                                        new BigDecimal("71.500000"),
                                        Instant.parse("2026-08-13T12:00:00Z"))));
        String requestFingerprint = telemetryBatchFingerprint.calculate(request);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first =
                    executor.submit(
                            () -> {
                                transactionTemplate.executeWithoutResult(
                                        transactionStatus -> {
                                            TelemetryBatchRepository.BatchRow batch =
                                                    telemetryBatchRepository
                                                            .tryCreate(
                                                                    UUID.randomUUID(),
                                                                    organisationId,
                                                                    request.idempotencyKey(),
                                                                    requestFingerprint,
                                                                    request.readings().size(),
                                                                    Instant.parse(
                                                                            "2026-08-13T12:30:00Z"))
                                                            .orElseThrow();
                                            telemetryBatchRepository.insertReadings(
                                                    organisationId, batch.id(), request.readings());
                                            telemetryProcessingEventRepository.insertBatchAccepted(
                                                    organisationId,
                                                    batch.id(),
                                                    Instant.parse("2026-08-13T12:30:00Z"));
                                            inserted.countDown();
                                            await(allowCommit);
                                        });
                            });
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<TelemetryBatchResponse> second =
                    executor.submit(() -> telemetryBatchService.accept(organisationId, request));

            assertThat(waitForBlockedTelemetryInsert()).isTrue();
            allowCommit.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS).idempotencyKey())
                    .isEqualTo("concurrent-exact");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
    }

    @Test
    void concurrentConflictingRetryPreservesTheCommittedWinner() throws Exception {
        UUID organisationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TelemetryBatchRequest winner =
                objectMapper.convertValue(
                        request("concurrent-conflict", NORTHSTAR_SENSOR, "71.500000"),
                        TelemetryBatchRequest.class);
        TelemetryBatchRequest conflicting =
                objectMapper.convertValue(
                        requestWithReadings(
                                winner.idempotencyKey(),
                                reading(NORTHSTAR_SENSOR, "81.500000"),
                                reading(NORTHSTAR_SENSOR, "82")),
                        TelemetryBatchRequest.class);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TelemetryBatchResponse accepted;

        try {
            Future<TelemetryBatchResponse> first =
                    executor.submit(
                            () ->
                                    transactionTemplate.execute(
                                            transactionStatus -> {
                                                TelemetryBatchResponse response =
                                                        telemetryBatchService.accept(
                                                                organisationId, winner);
                                                inserted.countDown();
                                                await(allowCommit);
                                                return response;
                                            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<TelemetryBatchResponse> second =
                    executor.submit(
                            () -> telemetryBatchService.accept(organisationId, conflicting));

            assertThat(waitForBlockedTelemetryInsert()).isTrue();
            allowCommit.countDown();
            accepted = first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TelemetryIdempotencyConflictException.class);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(telemetryBatchService.accept(organisationId, winner)).isEqualTo(accepted);
        TelemetryBatchRepository.BatchRow stored =
                telemetryBatchRepository.findByOrganisationIdAndIdempotencyKey(
                        organisationId, winner.idempotencyKey());
        assertThat(stored.id()).isEqualTo(accepted.batchId());
        assertThat(stored.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(stored.requestFingerprint())
                .isEqualTo(telemetryBatchFingerprint.calculate(winner));
        assertThat(stored.readingCount()).isOne();
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
        assertThat(
                        jdbcClient
                                .sql("SELECT value FROM telemetry_reading")
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("71.500000");
        assertThat(
                        jdbcClient
                                .sql("SELECT batch_id FROM telemetry_reading")
                                .query(UUID.class)
                                .single())
                .isEqualTo(accepted.batchId());
        assertThat(
                        jdbcClient
                                .sql("SELECT telemetry_batch_id FROM telemetry_processing_event")
                                .query(UUID.class)
                                .single())
                .isEqualTo(accepted.batchId());
    }

    @Test
    void theSameIdempotencyKeyIsIndependentAcrossOrganisations() throws Exception {
        AuthenticatedSession northstar = login("admin@northstar.example");
        AuthenticatedSession riverside = login("admin@riverside.example");

        String northstarBody =
                accept(northstar, request("shared-key", NORTHSTAR_SENSOR, "70"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String riversideBody =
                accept(riverside, request("shared-key", RIVERSIDE_SENSOR, "70"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(objectMapper.readTree(northstarBody).path("batchId").asText())
                .isNotEqualTo(objectMapper.readTree(riversideBody).path("batchId").asText());
        assertThat(count("telemetry_batch")).isEqualTo(2);
        assertThat(count("telemetry_reading")).isEqualTo(2);
        assertThat(count("telemetry_processing_event")).isEqualTo(2);
    }

    @Test
    void unknownAndForeignSensorsFailWithoutLeakingOrPersistingAnything() throws Exception {
        AuthenticatedSession authenticated = login("admin@northstar.example");

        for (String sensorId :
                new String[] {RIVERSIDE_SENSOR, "99999999-0000-0000-0000-000000000001"}) {
            accept(authenticated, request("invalid-" + sensorId.charAt(0), sensorId, "60"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_TELEMETRY_SENSOR"))
                    .andExpect(content().string(not(containsString(sensorId))));
        }

        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
    }

    @Test
    void mixedValidAndForeignBatchRollsBackAtomically() throws Exception {
        AuthenticatedSession authenticated = login("admin@northstar.example");
        Map<String, Object> request =
                Map.of(
                        "idempotencyKey",
                        "mixed-batch",
                        "readings",
                        new Object[] {
                            reading(NORTHSTAR_SENSOR, "70"), reading(RIVERSIDE_SENSOR, "80")
                        });

        accept(authenticated, request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TELEMETRY_SENSOR"));

        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
    }

    @Test
    void boundsValidationViewerDenialAnonymousDenialAndCsrfRemainEnforced() throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        Map<String, Object> empty = Map.of("idempotencyKey", "empty", "readings", new Object[0]);
        accept(admin, empty)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Map<String, Object> nullReading =
                Map.of("idempotencyKey", "null-reading", "readings", new Object[] {null});
        accept(admin, nullReading)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        Map<String, Object> tooMany =
                Map.of(
                        "idempotencyKey",
                        "too-many",
                        "readings",
                        Collections.nCopies(101, reading(NORTHSTAR_SENSOR, "70")));
        accept(admin, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        AuthenticatedSession viewer = login("viewer@northstar.example");
        accept(viewer, request("viewer-key", NORTHSTAR_SENSOR, "70"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        CsrfExchange anonymous = csrf(null);
        mockMvc.perform(
                        post(TELEMETRY_PATH)
                                .session(anonymous.session())
                                .header(anonymous.headerName(), anonymous.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                request("anonymous-key", NORTHSTAR_SENSOR, "70"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(
                        post(TELEMETRY_PATH)
                                .session(admin.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                request("no-csrf", NORTHSTAR_SENSOR, "70"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
    }

    @Test
    void fieldValidationRejectsInvalidValuesAndTimestampsWithoutPersistingAnything()
            throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        List<Map<String, Object>> invalidRequests =
                List.of(
                        requestWithReadings(
                                "value-out-of-range",
                                readingAt(
                                        NORTHSTAR_SENSOR,
                                        new BigDecimal("1000000000000.000001"),
                                        "2026-08-13T12:00:00Z")),
                        requestWithReadings(
                                "value-over-precision",
                                readingAt(
                                        NORTHSTAR_SENSOR,
                                        new BigDecimal("1.1234567"),
                                        "2026-08-13T12:00:00Z")),
                        requestWithReadings(
                                "future-timestamp",
                                readingAt(
                                        NORTHSTAR_SENSOR,
                                        new BigDecimal("70"),
                                        "2999-01-01T00:00:00Z")),
                        requestWithReadings(
                                "malformed-timestamp",
                                readingAt(
                                        NORTHSTAR_SENSOR, new BigDecimal("70"), "not-an-instant")),
                        requestWithReadings(
                                "mixed-field-validation",
                                reading(NORTHSTAR_SENSOR, "70"),
                                readingAt(
                                        NORTHSTAR_SENSOR,
                                        new BigDecimal("1000000000000.000001"),
                                        "2026-08-13T12:00:00Z")));

        for (Map<String, Object> invalidRequest : invalidRequests) {
            accept(admin, invalidRequest)
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1000000000000.0000010",
                "-1000000000000.0000010",
                "1.00000010",
                "-1.00000010",
                "0.00000010",
                "-0.00000010"
            })
    void invalidDecimalRejectsTheWholeBatchWithoutConsumingItsKey(String invalidValue)
            throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        Map<String, Object> invalidRequest =
                requestWithReadings(
                        "decimal-boundary",
                        reading(NORTHSTAR_SENSOR, "70"),
                        reading(NORTHSTAR_SENSOR, invalidValue));

        accept(admin, invalidRequest)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();

        Map<String, Object> corrected =
                requestWithReadings(
                        "decimal-boundary",
                        reading(NORTHSTAR_SENSOR, "70"),
                        reading(NORTHSTAR_SENSOR, "85"));
        String accepted =
                accept(admin, corrected)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.readingCount").value(2))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replayed =
                accept(admin, corrected)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(replayed).isEqualTo(accepted);
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isEqualTo(2);
        assertThat(count("telemetry_processing_event")).isOne();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-000001-12-31T23:59:59Z",
                "-000001-12-31T23:59:59.999999999Z",
                "0000-01-01T00:00:00+00:01",
                "-999999999-01-01T00:00:00Z",
                "-1000000000-01-01T00:00:00Z"
            })
    void unsupportedObservationRejectsTheWholeBatchWithoutConsumingItsKey(String observedAt)
            throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        Map<String, Object> invalidRequest =
                requestWithReadings(
                        "timestamp-boundary",
                        reading(NORTHSTAR_SENSOR, "70"),
                        readingAt(NORTHSTAR_SENSOR, new BigDecimal("85"), observedAt));

        accept(admin, invalidRequest)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString(observedAt))));

        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
        assertThat(count("alert")).isZero();

        Map<String, Object> corrected = request("timestamp-boundary", NORTHSTAR_SENSOR, "70");
        String accepted =
                accept(admin, corrected)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        accept(admin, corrected).andExpect(status().isOk()).andExpect(content().json(accepted));
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
    }

    @Test
    void earliestSupportedObservationRoundTripsAndRetainsExactRetryBehavior() throws Exception {
        AuthenticatedSession admin = login("admin@northstar.example");
        Map<String, Object> request =
                requestWithReadings(
                        "earliest-observation",
                        readingAt(
                                NORTHSTAR_SENSOR,
                                new BigDecimal("85"),
                                "0000-01-01T01:00:00+01:00"));

        String accepted =
                accept(admin, request)
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        accept(admin, request).andExpect(status().isOk()).andExpect(content().json(accepted));
        mockMvc.perform(
                        get("/api/v1/sensors/" + NORTHSTAR_SENSOR + "/telemetry-readings")
                                .session(admin.session())
                                .queryParam("from", "0000-01-01T00:00:00Z")
                                .queryParam("to", "0000-01-01T00:01:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("0000-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].observedAt").value("0000-01-01T00:00:00Z"));
        assertThat(count("telemetry_batch")).isOne();
        assertThat(count("telemetry_reading")).isOne();
        assertThat(count("telemetry_processing_event")).isOne();
    }

    private void assertEquivalentValueRetry(
            AuthenticatedSession admin,
            String idempotencyKey,
            String originalValue,
            String retryValue)
            throws Exception {
        String accepted =
                accept(admin, request(idempotencyKey, NORTHSTAR_SENSOR, originalValue))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.readingCount").value(1))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String replayed =
                accept(admin, request(idempotencyKey, NORTHSTAR_SENSOR, retryValue))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(replayed).isEqualTo(accepted);
        UUID batchId = UUID.fromString(objectMapper.readTree(accepted).path("batchId").asText());
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT value FROM telemetry_reading WHERE batch_id = :batchId")
                                .param("batchId", batchId)
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo(new BigDecimal(originalValue));
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            AuthenticatedSession authenticated, Object request) throws Exception {
        return mockMvc.perform(
                post(TELEMETRY_PATH)
                        .session(authenticated.session())
                        .header(authenticated.headerName(), authenticated.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)));
    }

    private Map<String, Object> request(String idempotencyKey, String sensorId, String value) {
        return Map.of(
                "idempotencyKey",
                idempotencyKey,
                "readings",
                new Object[] {reading(sensorId, value)});
    }

    private Map<String, Object> reading(String sensorId, String value) {
        return readingAt(sensorId, new BigDecimal(value), "2026-08-13T12:00:00Z");
    }

    private Map<String, Object> readingAt(String sensorId, BigDecimal value, String observedAt) {
        return Map.of("sensorId", sensorId, "value", value, "observedAt", observedAt);
    }

    @SafeVarargs
    private final Map<String, Object> requestWithReadings(
            String idempotencyKey, Map<String, Object>... readings) {
        return Map.of("idempotencyKey", idempotencyKey, "readings", readings);
    }

    private boolean waitForBlockedTelemetryInsert() throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            int blocked =
                    jdbcClient
                            .sql(
                                    """
                                    SELECT COUNT(*)::integer
                                    FROM pg_stat_activity
                                    WHERE pid <> pg_backend_pid()
                                      AND wait_event_type = 'Lock'
                                      AND query LIKE 'INSERT INTO telemetry_batch%'
                                    """)
                            .query(Integer.class)
                            .single();
            if (blocked > 0) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int count(String table) {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM " + table)
                .query(Integer.class)
                .single();
    }

    private AuthenticatedSession login(String email) throws Exception {
        CsrfExchange csrf = csrf(null);
        mockMvc.perform(
                        post(SESSION_PATH)
                                .session(csrf.session())
                                .header(csrf.headerName(), csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                Map.of("email", email, "password", DEMO_PASSWORD))))
                .andExpect(status().isOk());
        CsrfExchange authenticatedCsrf = csrf(csrf.session());
        return new AuthenticatedSession(
                authenticatedCsrf.session(),
                authenticatedCsrf.headerName(),
                authenticatedCsrf.token());
    }

    private CsrfExchange csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get(CSRF_PATH).accept(MediaType.APPLICATION_JSON);
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        MockHttpSession resolved = (MockHttpSession) result.getRequest().getSession(false);
        return new CsrfExchange(
                resolved, payload.path("headerName").asText(), payload.path("token").asText());
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}

    private record AuthenticatedSession(MockHttpSession session, String headerName, String token) {}
}
