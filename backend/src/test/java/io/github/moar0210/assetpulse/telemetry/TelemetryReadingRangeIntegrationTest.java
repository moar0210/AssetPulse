package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ASSETPULSE_SESSION_COOKIE_SECURE=false")
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class TelemetryReadingRangeIntegrationTest {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";
    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID MISSING_SENSOR_ID =
            UUID.fromString("99999999-0000-0000-0000-000000000001");
    private static final Instant RANGE_START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant RANGE_END = Instant.parse("2026-08-01T01:00:00Z");
    private static final String RANGE_INDEX =
            "ix_telemetry_reading_organisation_sensor_observed_id";

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

    @Test
    void payloadIsExactChronologicalAndUsesHalfOpenBounds() throws Exception {
        UUID fromId = UUID.fromString("51000000-0000-0000-0000-000000000001");
        UUID lowerTieId = UUID.fromString("51000000-0000-0000-0000-000000000002");
        UUID higherTieId = UUID.fromString("51000000-0000-0000-0000-000000000003");
        UUID lastId = UUID.fromString("51000000-0000-0000-0000-000000000004");
        insertReadings(
                NORTHSTAR_ID,
                NORTHSTAR_SENSOR_ID,
                List.of(
                        reading(
                                "51000000-0000-0000-0000-000000000010",
                                "1.000000",
                                RANGE_START.minusNanos(1_000)),
                        new ReadingFixture(fromId, new BigDecimal("10.125000"), RANGE_START),
                        new ReadingFixture(
                                higherTieId,
                                new BigDecimal("20.500000"),
                                RANGE_START.plusSeconds(1800)),
                        new ReadingFixture(
                                lowerTieId,
                                new BigDecimal("20.250000"),
                                RANGE_START.plusSeconds(1800)),
                        new ReadingFixture(
                                lastId, new BigDecimal("30.750000"), RANGE_END.minusNanos(1_000)),
                        reading("51000000-0000-0000-0000-000000000011", "99.000000", RANGE_END)));

        MvcResult result =
                mockMvc.perform(
                                rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END)
                                        .queryParam("limit", "500")
                                        .session(login("viewer@northstar.example")))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();

        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(payload.fieldNames())
                .toIterable()
                .containsExactly("sensorId", "from", "to", "readings");
        assertThat(payload.path("sensorId").asText()).isEqualTo(NORTHSTAR_SENSOR_ID.toString());
        assertThat(payload.path("from").asText()).isEqualTo(RANGE_START.toString());
        assertThat(payload.path("to").asText()).isEqualTo(RANGE_END.toString());
        assertReading(payload.path("readings").get(0), fromId, "10.125000", RANGE_START.toString());
        assertReading(
                payload.path("readings").get(1),
                lowerTieId,
                "20.250000",
                RANGE_START.plusSeconds(1800).toString());
        assertReading(
                payload.path("readings").get(2),
                higherTieId,
                "20.500000",
                RANGE_START.plusSeconds(1800).toString());
        assertReading(
                payload.path("readings").get(3),
                lastId,
                "30.750000",
                RANGE_END.minusNanos(1_000).toString());
        assertThat(payload.path("readings").size()).isEqualTo(4);
    }

    @Test
    void limitingSelectsMostRecentRowsThenReturnsThemChronologically() throws Exception {
        List<ReadingFixture> readings =
                IntStream.rangeClosed(1, 5)
                        .mapToObj(
                                index ->
                                        reading(
                                                "52000000-0000-0000-0000-%012d".formatted(index),
                                                "%d.000000".formatted(index),
                                                RANGE_START.plusSeconds(index * 60L)))
                        .toList();
        insertReadings(NORTHSTAR_ID, NORTHSTAR_SENSOR_ID, readings);

        JsonNode payload =
                readRange(
                        rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END)
                                .queryParam("limit", "2")
                                .session(login("technician@northstar.example")));

        assertThat(payload.path("readings").size()).isEqualTo(2);
        assertThat(payload.path("readings").get(0).path("id").asText())
                .isEqualTo(readings.get(3).id().toString());
        assertThat(payload.path("readings").get(1).path("id").asText())
                .isEqualTo(readings.get(4).id().toString());
    }

    @Test
    void omittedLimitDefaultsToTheMostRecentOneHundredRows() throws Exception {
        List<ReadingFixture> readings =
                IntStream.rangeClosed(1, 101)
                        .mapToObj(
                                index ->
                                        reading(
                                                "53000000-0000-0000-0000-%012d".formatted(index),
                                                "%d.000000".formatted(index),
                                                RANGE_START.plusSeconds(index)))
                        .toList();
        insertReadings(NORTHSTAR_ID, NORTHSTAR_SENSOR_ID, readings);

        JsonNode payload =
                readRange(
                        rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END)
                                .session(login("admin@northstar.example")));

        assertThat(payload.path("readings").size()).isEqualTo(100);
        assertThat(payload.path("readings").get(0).path("id").asText())
                .isEqualTo(readings.get(1).id().toString());
        assertThat(payload.path("readings").get(99).path("id").asText())
                .isEqualTo(readings.get(100).id().toString());
    }

    @Test
    void ownSensorWithNoReadingsReturnsAnEmptyRangeForTheFullAllowedSpan() throws Exception {
        Instant to = RANGE_START.plusSeconds(24 * 60 * 60);

        JsonNode payload =
                readRange(
                        rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, to)
                                .session(login("viewer@northstar.example")));

        assertThat(payload.path("sensorId").asText()).isEqualTo(NORTHSTAR_SENSOR_ID.toString());
        assertThat(payload.path("from").asText()).isEqualTo(RANGE_START.toString());
        assertThat(payload.path("to").asText()).isEqualTo(to.toString());
        assertThat(payload.path("readings").isArray()).isTrue();
        assertThat(payload.path("readings")).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("seededNorthstarRoles")
    void everySeededRoleMayReadTelemetry(String email) throws Exception {
        ReadingFixture reading =
                reading(
                        "54000000-0000-0000-0000-000000000001",
                        "41.500000",
                        RANGE_START.plusSeconds(60));
        insertReadings(NORTHSTAR_ID, NORTHSTAR_SENSOR_ID, List.of(reading));

        mockMvc.perform(
                        rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END)
                                .session(login(email)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].id").value(reading.id().toString()));
    }

    @Test
    void anonymousRequestsAreDeniedBeforeTelemetryIsResolved() throws Exception {
        mockMvc.perform(rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(content().string(not(containsString("Northstar"))))
                .andExpect(content().string(not(containsString("Bearing Temperature"))));
    }

    @Test
    void browserSuppliedTenantScopeIsIgnored() throws Exception {
        ReadingFixture ownReading =
                reading(
                        "55000000-0000-0000-0000-000000000001",
                        "42.000000",
                        RANGE_START.plusSeconds(60));
        ReadingFixture foreignReading =
                reading(
                        "55000000-0000-0000-0000-000000000002",
                        "999.000000",
                        RANGE_START.plusSeconds(60));
        insertReadings(NORTHSTAR_ID, NORTHSTAR_SENSOR_ID, List.of(ownReading));
        insertReadings(RIVERSIDE_ID, RIVERSIDE_SENSOR_ID, List.of(foreignReading));

        JsonNode payload =
                readRange(
                        rangeRequest(NORTHSTAR_SENSOR_ID, RANGE_START, RANGE_END)
                                .queryParam("organisationId", RIVERSIDE_ID.toString())
                                .queryParam("organisation", "riverside-manufacturing")
                                .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                .header("X-Organisation", "riverside-manufacturing")
                                .header("X-Role", "OPERATIONS_ADMIN")
                                .session(login("viewer@northstar.example")));

        assertThat(payload.path("readings").size()).isEqualTo(1);
        assertThat(payload.path("readings").get(0).path("id").asText())
                .isEqualTo(ownReading.id().toString());
        assertThat(payload.toString())
                .doesNotContain(foreignReading.id().toString())
                .doesNotContain("999.000000")
                .doesNotContain("organisationId");
    }

    @Test
    void foreignAndMissingSensorsReturnEquivalentNonLeakingProblems() throws Exception {
        MockHttpSession session = login("admin@northstar.example");
        List<JsonNode> problems = new ArrayList<>();

        for (UUID sensorId : List.of(RIVERSIDE_SENSOR_ID, MISSING_SENSOR_ID)) {
            MvcResult result =
                    mockMvc.perform(rangeRequest(sensorId, RANGE_START, RANGE_END).session(session))
                            .andExpect(status().isNotFound())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value("SENSOR_NOT_FOUND"))
                            .andExpect(jsonPath("$.title").value("Sensor not found"))
                            .andExpect(
                                    jsonPath("$.detail")
                                            .value(
                                                    "The requested sensor does not exist or is not accessible."))
                            .andExpect(content().string(not(containsString("Riverside"))))
                            .andExpect(content().string(not(containsString("Process Pump"))))
                            .andExpect(content().string(not(containsString("PUMP-201-TEMP"))))
                            .andReturn();
            problems.add(objectMapper.readTree(result.getResponse().getContentAsString()));
        }

        assertThat(problems.get(0).path("status")).isEqualTo(problems.get(1).path("status"));
        assertThat(problems.get(0).path("code")).isEqualTo(problems.get(1).path("code"));
        assertThat(problems.get(0).path("title")).isEqualTo(problems.get(1).path("title"));
        assertThat(problems.get(0).path("detail")).isEqualTo(problems.get(1).path("detail"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRanges")
    void invalidRangeAndLimitVariantsReturnOneStableProblem(
            String description, String from, String to, String limit) throws Exception {
        MockHttpServletRequestBuilder request =
                get(telemetryPath(NORTHSTAR_SENSOR_ID))
                        .session(login("viewer@northstar.example"))
                        .accept(MediaType.APPLICATION_JSON);
        if (from != null) {
            request.queryParam("from", from);
        }
        if (to != null) {
            request.queryParam("to", to);
        }
        if (limit != null) {
            request.queryParam("limit", limit);
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_TELEMETRY_RANGE"))
                .andExpect(jsonPath("$.title").value("Invalid telemetry range"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("Provide a valid telemetry time range and result limit."))
                .andExpect(content().string(not(containsString(description))));
    }

    @Test
    void telemetryReadingMutationsAreDeniedByDefault() throws Exception {
        MockHttpSession session = login("admin@northstar.example");
        CsrfExchange csrf = csrf(session);
        String path = telemetryPath(NORTHSTAR_SENSOR_ID);

        for (MockHttpServletRequestBuilder mutation :
                List.of(post(path), put(path), patch(path), delete(path))) {
            mockMvc.perform(
                            mutation.session(session)
                                    .header(csrf.headerName(), csrf.token())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    @Test
    void representativeRangePlanUsesTheCoveringIndex() {
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
                        SELECT
                            md5('range-plan-batch-' || batch_number)::uuid,
                            :organisationId,
                            'range-plan-' || batch_number,
                            repeat('a', 64),
                            100,
                            :acceptedAt
                        FROM generate_series(1, 200) batch_number
                        """)
                .param("organisationId", NORTHSTAR_ID)
                .param("acceptedAt", RANGE_START.atOffset(ZoneOffset.UTC))
                .update();
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
                        SELECT
                            md5('range-plan-reading-' || reading_number)::uuid,
                            :organisationId,
                            md5(
                                'range-plan-batch-'
                                || ((reading_number / 100) + 1)
                            )::uuid,
                            (reading_number % 100)::smallint,
                            :sensorId,
                            (reading_number % 1000)::numeric,
                            :rangeStart + make_interval(secs => reading_number)
                        FROM generate_series(0, 19999) reading_number
                        """)
                .param("organisationId", NORTHSTAR_ID)
                .param("sensorId", NORTHSTAR_SENSOR_ID)
                .param("rangeStart", RANGE_START.atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient.sql("ANALYZE telemetry_reading").update();

        String plan =
                String.join(
                        System.lineSeparator(),
                        jdbcClient
                                .sql(
                                        """
                                        EXPLAIN (COSTS OFF)
                                        SELECT id, value, observed_at
                                        FROM (
                                            SELECT id, value, observed_at
                                            FROM telemetry_reading
                                            WHERE organisation_id = :organisationId
                                              AND sensor_id = :sensorId
                                              AND observed_at >= :from
                                              AND observed_at < :to
                                            ORDER BY observed_at DESC, id DESC
                                            LIMIT :limit
                                        ) recent_readings
                                        ORDER BY observed_at, id
                                        """)
                                .param("organisationId", NORTHSTAR_ID)
                                .param("sensorId", NORTHSTAR_SENSOR_ID)
                                .param("from", RANGE_START.atOffset(ZoneOffset.UTC))
                                .param(
                                        "to",
                                        RANGE_START
                                                .plusSeconds(6 * 60 * 60)
                                                .atOffset(ZoneOffset.UTC))
                                .param("limit", 100)
                                .query(String.class)
                                .list());

        assertThat(plan).contains(RANGE_INDEX);
    }

    private JsonNode readRange(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder rangeRequest(UUID sensorId, Instant from, Instant to) {
        return get(telemetryPath(sensorId))
                .queryParam("from", from.toString())
                .queryParam("to", to.toString())
                .accept(MediaType.APPLICATION_JSON);
    }

    private String telemetryPath(UUID sensorId) {
        return "/api/v1/sensors/" + sensorId + "/telemetry-readings";
    }

    private void insertReadings(UUID organisationId, UUID sensorId, List<ReadingFixture> readings) {
        for (int offset = 0; offset < readings.size(); offset += 100) {
            List<ReadingFixture> batchReadings =
                    readings.subList(offset, Math.min(offset + 100, readings.size()));
            UUID batchId = UUID.randomUUID();
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
                                :id,
                                :organisationId,
                                :idempotencyKey,
                                :requestFingerprint,
                                :readingCount,
                                :acceptedAt
                            )
                            """)
                    .param("id", batchId)
                    .param("organisationId", organisationId)
                    .param("idempotencyKey", "range-test-" + batchId)
                    .param("requestFingerprint", "b".repeat(64))
                    .param("readingCount", batchReadings.size())
                    .param("acceptedAt", RANGE_START.atOffset(ZoneOffset.UTC))
                    .update();

            for (int sequence = 0; sequence < batchReadings.size(); sequence++) {
                ReadingFixture reading = batchReadings.get(sequence);
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
                                    :sequenceNumber,
                                    :sensorId,
                                    :value,
                                    :observedAt
                                )
                                """)
                        .param("id", reading.id())
                        .param("organisationId", organisationId)
                        .param("batchId", batchId)
                        .param("sequenceNumber", sequence)
                        .param("sensorId", sensorId)
                        .param("value", reading.value())
                        .param("observedAt", reading.observedAt().atOffset(ZoneOffset.UTC))
                        .update();
            }
        }
    }

    private void assertReading(
            JsonNode actual, UUID expectedId, String expectedValue, String expectedObservedAt) {
        assertThat(actual.fieldNames()).toIterable().containsExactly("id", "value", "observedAt");
        assertThat(actual.path("id").asText()).isEqualTo(expectedId.toString());
        assertThat(actual.path("value").decimalValue())
                .isEqualByComparingTo(new BigDecimal(expectedValue));
        assertThat(actual.path("observedAt").asText()).isEqualTo(expectedObservedAt);
    }

    private MockHttpSession login(String email) throws Exception {
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
        return csrf.session();
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

    private static ReadingFixture reading(String id, String value, Instant observedAt) {
        return new ReadingFixture(UUID.fromString(id), new BigDecimal(value), observedAt);
    }

    private static Stream<String> seededNorthstarRoles() {
        return Stream.of(
                "admin@northstar.example",
                "technician@northstar.example",
                "viewer@northstar.example");
    }

    private static Stream<Arguments> invalidRanges() {
        String from = RANGE_START.toString();
        String to = RANGE_END.toString();
        return Stream.of(
                Arguments.of("missing from", null, to, null),
                Arguments.of("missing to", from, null, null),
                Arguments.of("malformed from", "not-an-instant", to, null),
                Arguments.of("malformed to", from, "not-an-instant", null),
                Arguments.of("equal bounds", from, from, null),
                Arguments.of("reversed bounds", to, from, null),
                Arguments.of(
                        "more than twenty-four hours",
                        from,
                        RANGE_START.plusSeconds(24 * 60 * 60 + 1).toString(),
                        null),
                Arguments.of("empty limit", from, to, ""),
                Arguments.of("non-numeric limit", from, to, "many"),
                Arguments.of("zero limit", from, to, "0"),
                Arguments.of("negative limit", from, to, "-1"),
                Arguments.of("limit above maximum", from, to, "501"));
    }

    private record ReadingFixture(UUID id, BigDecimal value, Instant observedAt) {}

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}
}
