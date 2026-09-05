package io.github.moar0210.assetpulse.assets;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class AssetListIntegrationTest {

    private static final String ASSETS_PATH = "/api/v1/assets";
    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";
    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String NORTHSTAR_ASSET_ID = "20000000-0000-0000-0000-000000000001";
    private static final String RIVERSIDE_ASSET_ID = "20000000-0000-0000-0000-000000000003";
    private static final ExpectedAsset BOILER_FEED_PUMP =
            new ExpectedAsset(
                    "20000000-0000-0000-0000-000000000001", "PUMP-101", "Boiler Feed Pump");
    private static final ExpectedAsset COOLING_WATER_PUMP =
            new ExpectedAsset(
                    "20000000-0000-0000-0000-000000000002", "PUMP-102", "Cooling Water Pump");
    private static final ExpectedAsset PROCESS_PUMP =
            new ExpectedAsset("20000000-0000-0000-0000-000000000003", "PUMP-201", "Process Pump");

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
    void anonymousRequestsReceiveANonLeakingAuthenticationProblem() throws Exception {
        mockMvc.perform(get(ASSETS_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(content().string(not(containsString("Northstar"))))
                .andExpect(content().string(not(containsString("Riverside"))))
                .andExpect(content().string(not(containsString("Pump"))));
    }

    @ParameterizedTest
    @MethodSource("authorisedAccounts")
    void everySeededRoleReadsOnlyItsOrganisationsAssets(
            String email, List<ExpectedAsset> expectedAssets, List<ExpectedAsset> foreignAssets)
            throws Exception {
        JsonNode response = listAssets(login(email));

        assertExactAssets(response, expectedAssets);
        assertForeignAssetsAbsent(response, foreignAssets);
    }

    @ParameterizedTest
    @MethodSource("scopeSpoofAttempts")
    void browserSuppliedScopeCannotRevealAnotherOrganisationsAssetsOrCount(
            String email,
            UUID spoofedOrganisationId,
            List<ExpectedAsset> expectedAssets,
            List<ExpectedAsset> foreignAssets)
            throws Exception {
        MockHttpSession session = login(email);
        MvcResult result =
                mockMvc.perform(
                                get(ASSETS_PATH)
                                        .session(session)
                                        .queryParam(
                                                "organisationId", spoofedOrganisationId.toString())
                                        .queryParam("organisation", "foreign-tenant")
                                        .header(
                                                "X-Organisation-ID",
                                                spoofedOrganisationId.toString())
                                        .header("X-Organisation", "foreign-tenant")
                                        .header("X-Role", "OPERATIONS_ADMIN")
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());

        assertExactAssets(response, expectedAssets);
        assertForeignAssetsAbsent(response, foreignAssets);
    }

    @Test
    @Transactional
    void responseHasExplicitFieldsAndDeterministicNameThenIdOrder() throws Exception {
        UUID lowerId = UUID.fromString("21000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("21000000-0000-0000-0000-000000000002");
        insertAsset(higherId, NORTHSTAR_ID, "AUX-102", "Auxiliary Pump");
        insertAsset(lowerId, NORTHSTAR_ID, "AUX-101", "Auxiliary Pump");
        MockHttpSession session = login("admin@northstar.example");

        MvcResult first =
                mockMvc.perform(
                                get(ASSETS_PATH)
                                        .session(session)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        MvcResult second =
                mockMvc.perform(
                                get(ASSETS_PATH)
                                        .session(session)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode response = objectMapper.readTree(first.getResponse().getContentAsString());

        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(response.fieldNames()).toIterable().containsExactly("assets");
        assertThat(response.path("assets").size()).isEqualTo(4);
        assertThat(response.path("assets").get(0).path("id").asText())
                .isEqualTo(lowerId.toString());
        assertThat(response.path("assets").get(1).path("id").asText())
                .isEqualTo(higherId.toString());
        assertThat(assetNames(response))
                .containsExactly(
                        "Auxiliary Pump",
                        "Auxiliary Pump",
                        "Boiler Feed Pump",
                        "Cooling Water Pump");
        response.path("assets")
                .forEach(
                        asset ->
                                assertThat(asset.fieldNames())
                                        .toIterable()
                                        .containsExactly("id", "assetCode", "name"));
    }

    @Test
    @Transactional
    void organisationQueryHasAFixedOneHundredAssetBound() throws Exception {
        for (int index = 0; index < 101; index++) {
            insertAsset(
                    UUID.fromString("30000000-0000-0000-0000-%012d".formatted(index + 1)),
                    NORTHSTAR_ID,
                    "GENERATED-%03d".formatted(index),
                    "Generated Asset %03d".formatted(index));
        }
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT COUNT(*)::integer FROM asset WHERE organisation_id = :organisationId")
                                .param("organisationId", NORTHSTAR_ID)
                                .query(Integer.class)
                                .single())
                .isEqualTo(103);

        JsonNode response = listAssets(login("viewer@northstar.example"));

        assertThat(response.path("assets").size()).isEqualTo(100);
        assertThat(response.path("assets").get(0).path("name").asText())
                .isEqualTo("Boiler Feed Pump");
        assertThat(response.path("assets").get(99).path("name").asText())
                .isEqualTo("Generated Asset 097");
        assertThat(response.toString())
                .doesNotContain("Generated Asset 098")
                .doesNotContain("Generated Asset 099")
                .doesNotContain("Generated Asset 100");
    }

    @Test
    void assetMutationRoutesAreDeniedByDefault() throws Exception {
        MockHttpSession session = login("admin@northstar.example");
        CsrfExchange csrf = csrf(session);
        List<MockHttpServletRequestBuilder> mutations =
                List.of(
                        post(ASSETS_PATH),
                        put(ASSETS_PATH),
                        patch(ASSETS_PATH),
                        delete(ASSETS_PATH));

        for (MockHttpServletRequestBuilder mutation : mutations) {
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

    @ParameterizedTest
    @MethodSource("detailAccounts")
    void everySeededRoleReadsAnExactTenantScopedAssetDetail(
            String email,
            String assetId,
            String assetCode,
            String assetName,
            String sensorKey,
            String ruleCode)
            throws Exception {
        mockMvc.perform(
                        get(ASSETS_PATH + "/" + assetId)
                                .session(login(email))
                                .queryParam("organisationId", RIVERSIDE_ID.toString())
                                .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(assetId))
                .andExpect(jsonPath("$.assetCode").value(assetCode))
                .andExpect(jsonPath("$.name").value(assetName))
                .andExpect(jsonPath("$.sensors.length()").value(1))
                .andExpect(jsonPath("$.sensors[0].sensorKey").value(sensorKey))
                .andExpect(jsonPath("$.sensors[0].measurementType").value("TEMPERATURE"))
                .andExpect(jsonPath("$.sensors[0].unit").value("CELSIUS"))
                .andExpect(jsonPath("$.sensors[0].thresholdRules.length()").value(1))
                .andExpect(jsonPath("$.sensors[0].thresholdRules[0].ruleCode").value(ruleCode))
                .andExpect(
                        jsonPath("$.sensors[0].thresholdRules[0].comparison")
                                .value("GREATER_THAN_OR_EQUAL_TO"))
                .andExpect(jsonPath("$.sensors[0].thresholdRules[0].enabled").value(true))
                .andExpect(content().string(not(containsString("organisationId"))));
    }

    @Test
    @Transactional
    void assetDetailCapsSensorsAndRulesAtOneHundredWithDeterministicIdTieBreaks() throws Exception {
        UUID assetId = UUID.fromString("22000000-0000-0000-0000-000000000001");
        insertAsset(assetId, NORTHSTAR_ID, "BOUNDED-DETAIL", "Bounded Detail Asset");

        List<UUID> sensorIds = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            sensorIds.add(UUID.fromString("31000000-0000-0000-0000-%012d".formatted(index + 1)));
        }
        for (int index = sensorIds.size() - 1; index >= 0; index--) {
            insertSensor(
                    sensorIds.get(index),
                    NORTHSTAR_ID,
                    assetId,
                    "BOUNDED-SENSOR-%03d".formatted(index),
                    "Bounded Sensor");
        }

        List<UUID> ruleIds = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            ruleIds.add(UUID.fromString("41000000-0000-0000-0000-%012d".formatted(index + 1)));
        }
        for (int index = ruleIds.size() - 1; index >= 0; index--) {
            insertThresholdRule(
                    ruleIds.get(index),
                    NORTHSTAR_ID,
                    sensorIds.getFirst(),
                    "BOUNDED-RULE-%03d".formatted(index),
                    "Bounded Rule");
        }

        MvcResult result =
                mockMvc.perform(
                                get(ASSETS_PATH + "/" + assetId)
                                        .session(login("viewer@northstar.example"))
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode sensors = response.path("sensors");
        JsonNode thresholdRules = sensors.get(0).path("thresholdRules");

        assertThat(sensors).hasSize(100);
        assertThat(ids(sensors))
                .containsExactlyElementsOf(
                        sensorIds.stream().limit(100).map(UUID::toString).toList());
        assertThat(thresholdRules).hasSize(100);
        assertThat(ids(thresholdRules))
                .containsExactlyElementsOf(
                        ruleIds.stream().limit(100).map(UUID::toString).toList());
        assertThat(response.toString())
                .doesNotContain(sensorIds.get(100).toString())
                .doesNotContain(ruleIds.get(100).toString());
    }

    @Test
    void missingAndForeignAssetIdsReturnTheSameNonLeakingProblem() throws Exception {
        MockHttpSession session = login("admin@northstar.example");

        for (String assetId : List.of(RIVERSIDE_ASSET_ID, "99999999-0000-0000-0000-000000000001")) {
            mockMvc.perform(
                            get(ASSETS_PATH + "/" + assetId)
                                    .session(session)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(
                            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "The requested asset does not exist or is not accessible."))
                    .andExpect(content().string(not(containsString("Riverside"))))
                    .andExpect(content().string(not(containsString("Process Pump"))));
        }
    }

    @Test
    void malformedAssetIdReturnsATypedNonReflectingProblem() throws Exception {
        mockMvc.perform(
                        get(ASSETS_PATH + "/not-a-uuid")
                                .session(login("viewer@northstar.example"))
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PATH_PARAMETER"))
                .andExpect(jsonPath("$.title").value("Invalid path parameter"))
                .andExpect(jsonPath("$.detail").value("One or more path parameters are invalid."));
    }

    @Test
    void assetDetailMutationRoutesAreDeniedByDefault() throws Exception {
        MockHttpSession session = login("admin@northstar.example");
        CsrfExchange csrf = csrf(session);
        for (MockHttpServletRequestBuilder mutation :
                List.of(
                        post(ASSETS_PATH + "/" + NORTHSTAR_ASSET_ID),
                        put(ASSETS_PATH + "/" + NORTHSTAR_ASSET_ID),
                        patch(ASSETS_PATH + "/" + NORTHSTAR_ASSET_ID),
                        delete(ASSETS_PATH + "/" + NORTHSTAR_ASSET_ID))) {
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

    private JsonNode listAssets(MockHttpSession session) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(ASSETS_PATH)
                                        .session(session)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<String> assetNames(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.path("assets").forEach(asset -> names.add(asset.path("name").asText()));
        return names;
    }

    private List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(element -> ids.add(element.path("id").asText()));
        return ids;
    }

    private void assertExactAssets(JsonNode response, List<ExpectedAsset> expectedAssets) {
        assertThat(response.fieldNames()).toIterable().containsExactly("assets");
        assertThat(response.path("assets").size()).isEqualTo(expectedAssets.size());

        for (int index = 0; index < expectedAssets.size(); index++) {
            ExpectedAsset expected = expectedAssets.get(index);
            JsonNode actual = response.path("assets").get(index);
            assertThat(actual.fieldNames()).toIterable().containsExactly("id", "assetCode", "name");
            assertThat(actual.path("id").asText()).isEqualTo(expected.id());
            assertThat(actual.path("assetCode").asText()).isEqualTo(expected.assetCode());
            assertThat(actual.path("name").asText()).isEqualTo(expected.name());
        }
    }

    private void assertForeignAssetsAbsent(JsonNode response, List<ExpectedAsset> foreignAssets) {
        assertThat(response.has("totalCount")).isFalse();
        String payload = response.toString();
        foreignAssets.forEach(
                asset ->
                        assertThat(payload)
                                .doesNotContain(asset.id())
                                .doesNotContain(asset.assetCode())
                                .doesNotContain(asset.name()));
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

    private void insertAsset(UUID id, UUID organisationId, String assetCode, String assetName) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO asset (id, organisation_id, asset_code, name)
                        VALUES (:id, :organisationId, :assetCode, :assetName)
                        """)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("assetCode", assetCode)
                .param("assetName", assetName)
                .update();
    }

    private void insertSensor(
            UUID id, UUID organisationId, UUID assetId, String sensorKey, String sensorName) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO sensor (
                            id, organisation_id, asset_id, sensor_key, name,
                            measurement_type, unit
                        )
                        VALUES (
                            :id, :organisationId, :assetId, :sensorKey, :sensorName,
                            'TEMPERATURE', 'CELSIUS'
                        )
                        """)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("assetId", assetId)
                .param("sensorKey", sensorKey)
                .param("sensorName", sensorName)
                .update();
    }

    private void insertThresholdRule(
            UUID id, UUID organisationId, UUID sensorId, String ruleCode, String ruleName) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO threshold_rule (
                            id, organisation_id, sensor_id, rule_code, name, comparison,
                            threshold_value, cooldown_seconds, enabled
                        )
                        VALUES (
                            :id, :organisationId, :sensorId, :ruleCode, :ruleName,
                            'GREATER_THAN_OR_EQUAL_TO', 80.000000, 300, TRUE
                        )
                        """)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("sensorId", sensorId)
                .param("ruleCode", ruleCode)
                .param("ruleName", ruleName)
                .update();
    }

    private static Stream<Arguments> authorisedAccounts() {
        return Stream.of(
                Arguments.of(
                        "admin@northstar.example",
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP),
                        List.of(PROCESS_PUMP)),
                Arguments.of(
                        "technician@northstar.example",
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP),
                        List.of(PROCESS_PUMP)),
                Arguments.of(
                        "viewer@northstar.example",
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP),
                        List.of(PROCESS_PUMP)),
                Arguments.of(
                        "admin@riverside.example",
                        List.of(PROCESS_PUMP),
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP)));
    }

    private static Stream<Arguments> scopeSpoofAttempts() {
        return Stream.of(
                Arguments.of(
                        "admin@northstar.example",
                        RIVERSIDE_ID,
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP),
                        List.of(PROCESS_PUMP)),
                Arguments.of(
                        "admin@riverside.example",
                        NORTHSTAR_ID,
                        List.of(PROCESS_PUMP),
                        List.of(BOILER_FEED_PUMP, COOLING_WATER_PUMP)));
    }

    private static Stream<Arguments> detailAccounts() {
        return Stream.of(
                Arguments.of(
                        "admin@northstar.example",
                        NORTHSTAR_ASSET_ID,
                        "PUMP-101",
                        "Boiler Feed Pump",
                        "PUMP-101-TEMP",
                        "PUMP-101-HIGH-TEMP"),
                Arguments.of(
                        "technician@northstar.example",
                        NORTHSTAR_ASSET_ID,
                        "PUMP-101",
                        "Boiler Feed Pump",
                        "PUMP-101-TEMP",
                        "PUMP-101-HIGH-TEMP"),
                Arguments.of(
                        "viewer@northstar.example",
                        NORTHSTAR_ASSET_ID,
                        "PUMP-101",
                        "Boiler Feed Pump",
                        "PUMP-101-TEMP",
                        "PUMP-101-HIGH-TEMP"),
                Arguments.of(
                        "admin@riverside.example",
                        RIVERSIDE_ASSET_ID,
                        "PUMP-201",
                        "Process Pump",
                        "PUMP-201-TEMP",
                        "PUMP-201-HIGH-TEMP"));
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}

    private record ExpectedAsset(String id, String assetCode, String name) {}
}
