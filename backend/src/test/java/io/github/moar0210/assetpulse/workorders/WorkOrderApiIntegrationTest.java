package io.github.moar0210.assetpulse.workorders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = "ASSETPULSE_SESSION_COOKIE_SECURE=false")
@AutoConfigureMockMvc
@Testcontainers
class WorkOrderApiIntegrationTest {

    private static final String WORK_ORDERS_PATH = "/api/v1/work-orders";
    private static final String ELIGIBLE_TECHNICIANS_PATH =
            WORK_ORDERS_PATH + "/eligible-technicians";
    private static final String SESSION_PATH = "/api/v1/session";
    private static final String CSRF_PATH = SESSION_PATH + "/csrf";
    private static final String DEMO_PASSWORD = "AssetPulse1!";

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_TECHNICIAN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_VIEWER_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID RIVERSIDE_TECHNICIAN_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SECOND_TECHNICIAN_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000002");
    private static final UUID NORTHSTAR_RULE_ONE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_TWO_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID RIVERSIDE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID NORTHSTAR_ALERT_ONE_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000101");
    private static final UUID NORTHSTAR_ALERT_TWO_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000102");
    private static final UUID RIVERSIDE_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000103");
    private static final UUID NORTHSTAR_ALERT_THREE_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000104");
    private static final UUID NORTHSTAR_WORK_ORDER_ONE_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000101");
    private static final UUID NORTHSTAR_WORK_ORDER_TWO_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000102");
    private static final UUID RIVERSIDE_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000103");
    private static final UUID NORTHSTAR_WORK_ORDER_THREE_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000104");
    private static final UUID MISSING_ID = UUID.fromString("99999999-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T10:00:00Z");

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
    void clearWorkOrderFixtures() {
        jdbcClient.sql("DELETE FROM work_order").update();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();
        jdbcClient.sql("DELETE FROM app_user WHERE email LIKE 'workorder-test-%'").update();
    }

    @Test
    void adminCreatesAnExactSafeOpenWorkOrderWithLocation() throws Exception {
        insertAlert(NORTHSTAR_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        AuthenticatedSession admin = login("admin@northstar.example");

        MvcResult result =
                mockMvc.perform(
                                create(admin, NORTHSTAR_ALERT_ONE_ID)
                                        .queryParam("organisationId", RIVERSIDE_ID.toString())
                                        .header("X-Organisation-ID", RIVERSIDE_ID.toString())
                                        .header("X-Role", "VIEWER"))
                        .andExpect(status().isCreated())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();

        JsonNode payload = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID workOrderId = UUID.fromString(payload.path("id").asText());
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo(WORK_ORDERS_PATH + "/" + workOrderId);
        assertExactWorkOrder(payload, workOrderId, NORTHSTAR_ALERT_ONE_ID, "OPEN", 0, false);
        assertSafe(payload);
        assertThat(payload.path("createdAt")).isEqualTo(payload.path("updatedAt"));

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS(
                                            '|',
                                            organisation_id,
                                            alert_id,
                                            status,
                                            version,
                                            COALESCE(assigned_technician_user_id::text, '-'),
                                            COALESCE(assigned_at::text, '-')
                                        )
                                        FROM work_order
                                        WHERE id = :workOrderId
                                        """)
                                .param("workOrderId", workOrderId)
                                .query(String.class)
                                .single())
                .isEqualTo(NORTHSTAR_ID + "|" + NORTHSTAR_ALERT_ONE_ID + "|OPEN|0|-|-");
    }

    @Test
    void missingAndForeignAlertsAreEquivalentAndDuplicateCreationIsRaceSafe() throws Exception {
        insertAlert(NORTHSTAR_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        insertAlert(RIVERSIDE_ALERT_ID, RIVERSIDE_ID, RIVERSIDE_RULE_ID, "OPEN", 2);
        AuthenticatedSession firstAdmin = login("admin@northstar.example");
        AuthenticatedSession secondAdmin = login("admin@northstar.example");

        JsonNode missing =
                problem(create(firstAdmin, MISSING_ID), 404, "WORK_ORDER_SOURCE_ALERT_NOT_FOUND");
        JsonNode foreign =
                problem(
                        create(firstAdmin, RIVERSIDE_ALERT_ID),
                        404,
                        "WORK_ORDER_SOURCE_ALERT_NOT_FOUND");
        assertEquivalentProblems(missing, foreign);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first =
                    executor.submit(
                            () ->
                                    concurrentCreate(
                                            firstAdmin, ready, start, NORTHSTAR_ALERT_ONE_ID));
            Future<MvcResult> second =
                    executor.submit(
                            () ->
                                    concurrentCreate(
                                            secondAdmin, ready, start, NORTHSTAR_ALERT_ONE_ID));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = List.of(first.get(), second.get());
            assertThat(results)
                    .extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(
                            objectMapper
                                    .readTree(
                                            results.stream()
                                                    .filter(
                                                            result ->
                                                                    result.getResponse().getStatus()
                                                                            == 409)
                                                    .findFirst()
                                                    .orElseThrow()
                                                    .getResponse()
                                                    .getContentAsString())
                                    .path("code")
                                    .asText())
                    .isEqualTo("WORK_ORDER_ALREADY_EXISTS");
        }

        assertThat(count("work_order")).isOne();
        problem(create(firstAdmin, NORTHSTAR_ALERT_ONE_ID), 409, "WORK_ORDER_ALREADY_EXISTS");
        assertThat(count("work_order")).isOne();
    }

    @Test
    void readsAreTenantScopedAndTechniciansSeeOnlyTheirAssignments() throws Exception {
        insertAlert(NORTHSTAR_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        insertAlert(NORTHSTAR_ALERT_TWO_ID, NORTHSTAR_ID, NORTHSTAR_RULE_TWO_ID, "OPEN", 2);
        insertAlert(RIVERSIDE_ALERT_ID, RIVERSIDE_ID, RIVERSIDE_RULE_ID, "OPEN", 3);
        insertAlert(NORTHSTAR_ALERT_THREE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "RESOLVED", 4);
        insertTestTechnician(NORTHSTAR_SECOND_TECHNICIAN_ID, NORTHSTAR_ID, 200);
        insertOpenWorkOrder(
                NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_ID, NORTHSTAR_ALERT_ONE_ID, CREATED_AT);
        insertAssignedWorkOrder(
                NORTHSTAR_WORK_ORDER_TWO_ID,
                NORTHSTAR_ID,
                NORTHSTAR_ALERT_TWO_ID,
                NORTHSTAR_TECHNICIAN_ID,
                CREATED_AT.plusSeconds(10));
        insertOpenWorkOrder(RIVERSIDE_WORK_ORDER_ID, RIVERSIDE_ID, RIVERSIDE_ALERT_ID, CREATED_AT);
        insertAssignedWorkOrder(
                NORTHSTAR_WORK_ORDER_THREE_ID,
                NORTHSTAR_ID,
                NORTHSTAR_ALERT_THREE_ID,
                NORTHSTAR_SECOND_TECHNICIAN_ID,
                CREATED_AT.plusSeconds(20));

        for (String email : List.of("admin@northstar.example", "viewer@northstar.example")) {
            JsonNode payload = list(login(email).session(), null);
            assertThat(payload.path("limit").asInt()).isEqualTo(50);
            assertThat(payload.path("workOrders")).hasSize(3);
            assertSafe(payload);
        }

        MockHttpSession technician = login("technician@northstar.example").session();
        JsonNode technicianList = list(technician, null);
        assertThat(technicianList.path("workOrders")).hasSize(1);
        assertThat(technicianList.path("workOrders").get(0).path("id").asText())
                .isEqualTo(NORTHSTAR_WORK_ORDER_TWO_ID.toString());
        assertExactWorkOrder(
                detail(technician, NORTHSTAR_WORK_ORDER_TWO_ID),
                NORTHSTAR_WORK_ORDER_TWO_ID,
                NORTHSTAR_ALERT_TWO_ID,
                "ASSIGNED",
                1,
                true);
        problem(
                get(WORK_ORDERS_PATH + "/" + NORTHSTAR_WORK_ORDER_ONE_ID)
                        .session(technician)
                        .accept(MediaType.APPLICATION_JSON),
                404,
                "WORK_ORDER_NOT_FOUND");
        problem(
                get(WORK_ORDERS_PATH + "/" + NORTHSTAR_WORK_ORDER_THREE_ID)
                        .session(technician)
                        .accept(MediaType.APPLICATION_JSON),
                404,
                "WORK_ORDER_NOT_FOUND");

        JsonNode secondTechnicianList =
                list(login("workorder-test-200@example.test").session(), null);
        assertThat(secondTechnicianList.path("workOrders")).hasSize(1);
        assertThat(secondTechnicianList.path("workOrders").get(0).path("id").asText())
                .isEqualTo(NORTHSTAR_WORK_ORDER_THREE_ID.toString());

        MockHttpSession admin = login("admin@northstar.example").session();
        JsonNode foreign =
                problem(
                        get(WORK_ORDERS_PATH + "/" + RIVERSIDE_WORK_ORDER_ID)
                                .session(admin)
                                .accept(MediaType.APPLICATION_JSON),
                        404,
                        "WORK_ORDER_NOT_FOUND");
        JsonNode missing =
                problem(
                        get(WORK_ORDERS_PATH + "/" + MISSING_ID)
                                .session(admin)
                                .accept(MediaType.APPLICATION_JSON),
                        404,
                        "WORK_ORDER_NOT_FOUND");
        assertEquivalentProblems(foreign, missing);

        JsonNode spoofed =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get(WORK_ORDERS_PATH)
                                                .session(technician)
                                                .queryParam(
                                                        "organisationId", RIVERSIDE_ID.toString())
                                                .header(
                                                        "X-Organisation-ID",
                                                        RIVERSIDE_ID.toString())
                                                .header("X-Role", "OPERATIONS_ADMIN")
                                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(spoofed.path("workOrders")).hasSize(1);
        assertSafe(spoofed);
    }

    @Test
    void listDefaultsToFiftyCapsAtOneHundredAndRejectsInvalidQueries() throws Exception {
        for (int index = 1; index <= 101; index++) {
            UUID alertId = generatedAlertId(index);
            UUID workOrderId = generatedWorkOrderId(index);
            insertAlert(alertId, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "RESOLVED", 1_000 + index);
            insertOpenWorkOrder(workOrderId, NORTHSTAR_ID, alertId, CREATED_AT.plusSeconds(index));
        }
        MockHttpSession viewer = login("viewer@northstar.example").session();

        JsonNode defaultPage = list(viewer, null);
        assertThat(defaultPage.path("limit").asInt()).isEqualTo(50);
        assertThat(defaultPage.path("workOrders")).hasSize(50);
        JsonNode maximumPage = list(viewer, "100");
        assertThat(maximumPage.path("limit").asInt()).isEqualTo(100);
        assertThat(maximumPage.path("workOrders")).hasSize(100);

        for (String invalid : List.of("", "0", "101", "-1", "1.5", "abc", "01", " 1")) {
            problem(
                    get(WORK_ORDERS_PATH)
                            .session(viewer)
                            .queryParam("limit", invalid)
                            .accept(MediaType.APPLICATION_JSON),
                    400,
                    "INVALID_WORK_ORDER_QUERY");
        }
    }

    @Test
    void eligibleTechniciansAreAdminOnlyTenantScopedSafeAndHardBounded() throws Exception {
        insertTestTechnician(RIVERSIDE_TECHNICIAN_ID, RIVERSIDE_ID, 999);
        AuthenticatedSession northstarAdmin = login("admin@northstar.example");

        JsonNode seeded =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get(ELIGIBLE_TECHNICIANS_PATH)
                                                .session(northstarAdmin.session())
                                                .queryParam(
                                                        "organisationId", RIVERSIDE_ID.toString())
                                                .header(
                                                        "X-Organisation-ID",
                                                        RIVERSIDE_ID.toString())
                                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andExpect(header().string("Cache-Control", "no-store"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(seeded.fieldNames()).toIterable().containsExactly("technicians");
        assertThat(seeded.path("technicians")).hasSize(1);
        assertThat(seeded.path("technicians").get(0).fieldNames())
                .toIterable()
                .containsExactly("id", "displayName");
        assertThat(seeded.path("technicians").get(0).path("id").asText())
                .isEqualTo(NORTHSTAR_TECHNICIAN_ID.toString());
        assertThat(seeded.toString()).doesNotContain(RIVERSIDE_TECHNICIAN_ID.toString());

        for (int index = 1; index <= 105; index++) {
            insertTestTechnician(generatedTechnicianId(index), NORTHSTAR_ID, index);
        }
        JsonNode bounded =
                objectMapper.readTree(
                        mockMvc.perform(
                                        get(ELIGIBLE_TECHNICIANS_PATH)
                                                .session(northstarAdmin.session())
                                                .accept(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        assertThat(bounded.path("technicians")).hasSize(100);
        assertSafe(bounded);

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            MockHttpSession denied = login(email).session();
            mockMvc.perform(
                            get(ELIGIBLE_TECHNICIANS_PATH)
                                    .session(denied)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(head(ELIGIBLE_TECHNICIANS_PATH).session(denied))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(head(ELIGIBLE_TECHNICIANS_PATH).session(northstarAdmin.session()))
                .andExpect(status().isOk());
    }

    @Test
    void assignmentIsAtomicVersionedAndRejectsEveryInvalidAssigneeWithoutMutation()
            throws Exception {
        insertAlert(NORTHSTAR_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        insertOpenWorkOrder(
                NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_ID, NORTHSTAR_ALERT_ONE_ID, CREATED_AT);
        insertTestTechnician(RIVERSIDE_TECHNICIAN_ID, RIVERSIDE_ID, 999);
        AuthenticatedSession admin = login("admin@northstar.example");

        for (UUID invalidAssignee :
                List.of(
                        NORTHSTAR_ADMIN_ID,
                        NORTHSTAR_VIEWER_ID,
                        RIVERSIDE_TECHNICIAN_ID,
                        MISSING_ID)) {
            problem(
                    assign(admin, NORTHSTAR_WORK_ORDER_ONE_ID, invalidAssignee, 0L),
                    400,
                    "INVALID_WORK_ORDER_ASSIGNEE");
            assertOpenUnchanged(NORTHSTAR_WORK_ORDER_ONE_ID);
        }
        problem(
                post(WORK_ORDERS_PATH + "/" + NORTHSTAR_WORK_ORDER_ONE_ID + "/assign")
                        .session(admin.session())
                        .header(admin.csrfHeaderName(), admin.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"),
                400,
                "INVALID_WORK_ORDER_ASSIGNEE");
        assertOpenUnchanged(NORTHSTAR_WORK_ORDER_ONE_ID);

        problem(
                assign(admin, NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_TECHNICIAN_ID, 1L),
                409,
                "WORK_ORDER_STATE_CONFLICT");
        assertOpenUnchanged(NORTHSTAR_WORK_ORDER_ONE_ID);

        for (String invalidVersion : List.of("0.5", "\"0\"")) {
            problem(
                    assignWithRawExpectedVersion(
                            admin,
                            NORTHSTAR_WORK_ORDER_ONE_ID,
                            NORTHSTAR_TECHNICIAN_ID,
                            invalidVersion),
                    400,
                    "INVALID_REQUEST");
            assertOpenUnchanged(NORTHSTAR_WORK_ORDER_ONE_ID);
        }

        MvcResult result =
                mockMvc.perform(
                                assign(
                                        admin,
                                        NORTHSTAR_WORK_ORDER_ONE_ID,
                                        NORTHSTAR_TECHNICIAN_ID,
                                        0L))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        JsonNode assigned = objectMapper.readTree(result.getResponse().getContentAsString());
        assertExactWorkOrder(
                assigned, NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_ALERT_ONE_ID, "ASSIGNED", 1, true);
        assertThat(assigned.path("updatedAt").asText())
                .isGreaterThanOrEqualTo(assigned.path("createdAt").asText());

        String rowAfterSuccess = assignmentState(NORTHSTAR_WORK_ORDER_ONE_ID);
        problem(
                assign(admin, NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_TECHNICIAN_ID, 0L),
                409,
                "WORK_ORDER_STATE_CONFLICT");
        assertThat(assignmentState(NORTHSTAR_WORK_ORDER_ONE_ID)).isEqualTo(rowAfterSuccess);
    }

    @Test
    void missingAndForeignWorkOrdersReturnEquivalentNotFoundBeforeAssigneeValidation()
            throws Exception {
        insertAlert(RIVERSIDE_ALERT_ID, RIVERSIDE_ID, RIVERSIDE_RULE_ID, "OPEN", 1);
        insertOpenWorkOrder(RIVERSIDE_WORK_ORDER_ID, RIVERSIDE_ID, RIVERSIDE_ALERT_ID, CREATED_AT);
        AuthenticatedSession admin = login("admin@northstar.example");

        JsonNode missing =
                problem(assign(admin, MISSING_ID, null, 0L), 404, "WORK_ORDER_NOT_FOUND");
        JsonNode foreign =
                problem(
                        assign(admin, RIVERSIDE_WORK_ORDER_ID, null, 0L),
                        404,
                        "WORK_ORDER_NOT_FOUND");
        assertEquivalentProblems(missing, foreign);
        assertThat(assignmentState(RIVERSIDE_WORK_ORDER_ID)).startsWith("OPEN|0|-|-|-|");
    }

    @Test
    void mutationsRequireAdminRoleAndValidCsrf() throws Exception {
        insertAlert(NORTHSTAR_ALERT_ONE_ID, NORTHSTAR_ID, NORTHSTAR_RULE_ONE_ID, "OPEN", 1);
        insertOpenWorkOrder(
                NORTHSTAR_WORK_ORDER_ONE_ID, NORTHSTAR_ID, NORTHSTAR_ALERT_ONE_ID, CREATED_AT);

        for (String email : List.of("technician@northstar.example", "viewer@northstar.example")) {
            AuthenticatedSession denied = login(email);
            mockMvc.perform(create(denied, NORTHSTAR_ALERT_ONE_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mockMvc.perform(
                            assign(
                                    denied,
                                    NORTHSTAR_WORK_ORDER_ONE_ID,
                                    NORTHSTAR_TECHNICIAN_ID,
                                    0L))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        AuthenticatedSession admin = login("admin@northstar.example");
        mockMvc.perform(
                        post(WORK_ORDERS_PATH)
                                .session(admin.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                Map.of("alertId", NORTHSTAR_ALERT_ONE_ID))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mockMvc.perform(
                        post(WORK_ORDERS_PATH + "/" + NORTHSTAR_WORK_ORDER_ONE_ID + "/assign")
                                .session(admin.session())
                                .header(admin.csrfHeaderName(), "invalid-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                Map.of(
                                                        "technicianUserId",
                                                        NORTHSTAR_TECHNICIAN_ID,
                                                        "expectedVersion",
                                                        0))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        CsrfExchange anonymous = csrf(null);
        mockMvc.perform(
                        post(WORK_ORDERS_PATH)
                                .session(anonymous.session())
                                .header(anonymous.headerName(), anonymous.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        objectMapper.writeValueAsBytes(
                                                Map.of("alertId", NORTHSTAR_ALERT_ONE_ID))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        assertThat(count("work_order")).isOne();
        assertOpenUnchanged(NORTHSTAR_WORK_ORDER_ONE_ID);
    }

    private JsonNode list(MockHttpSession session, String limit) throws Exception {
        MockHttpServletRequestBuilder request =
                get(WORK_ORDERS_PATH).session(session).accept(MediaType.APPLICATION_JSON);
        if (limit != null) {
            request.queryParam("limit", limit);
        }
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode detail(MockHttpSession session, UUID workOrderId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get(WORK_ORDERS_PATH + "/" + workOrderId)
                                        .session(session)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder create(AuthenticatedSession authenticated, UUID alertId)
            throws Exception {
        return post(WORK_ORDERS_PATH)
                .session(authenticated.session())
                .header(authenticated.csrfHeaderName(), authenticated.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("alertId", alertId)));
    }

    private MockHttpServletRequestBuilder assign(
            AuthenticatedSession authenticated,
            UUID workOrderId,
            UUID technicianUserId,
            Long expectedVersion)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("technicianUserId", technicianUserId);
        body.put("expectedVersion", expectedVersion);
        return post(WORK_ORDERS_PATH + "/" + workOrderId + "/assign")
                .session(authenticated.session())
                .header(authenticated.csrfHeaderName(), authenticated.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body));
    }

    private MockHttpServletRequestBuilder assignWithRawExpectedVersion(
            AuthenticatedSession authenticated,
            UUID workOrderId,
            UUID technicianUserId,
            String rawExpectedVersion) {
        return post(WORK_ORDERS_PATH + "/" + workOrderId + "/assign")
                .session(authenticated.session())
                .header(authenticated.csrfHeaderName(), authenticated.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(
                        "{\"technicianUserId\":\""
                                + technicianUserId
                                + "\",\"expectedVersion\":"
                                + rawExpectedVersion
                                + "}");
    }

    private MvcResult concurrentCreate(
            AuthenticatedSession authenticated,
            CountDownLatch ready,
            CountDownLatch start,
            UUID alertId)
            throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting to create work orders concurrently");
        }
        return mockMvc.perform(create(authenticated, alertId)).andReturn();
    }

    private JsonNode problem(
            MockHttpServletRequestBuilder request, int expectedStatus, String expectedCode)
            throws Exception {
        MvcResult result =
                mockMvc.perform(request)
                        .andExpect(status().is(expectedStatus))
                        .andExpect(
                                content()
                                        .contentTypeCompatibleWith(
                                                MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value(expectedCode))
                        .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertExactWorkOrder(
            JsonNode payload,
            UUID workOrderId,
            UUID alertId,
            String expectedStatus,
            long expectedVersion,
            boolean assigned) {
        assertThat(payload.fieldNames())
                .toIterable()
                .containsExactly(
                        "id",
                        "alertId",
                        "status",
                        "version",
                        "assignedTechnician",
                        "createdAt",
                        "updatedAt",
                        "context");
        assertThat(payload.path("id").asText()).isEqualTo(workOrderId.toString());
        assertThat(payload.path("alertId").asText()).isEqualTo(alertId.toString());
        assertThat(payload.path("status").asText()).isEqualTo(expectedStatus);
        assertThat(payload.path("version").asLong()).isEqualTo(expectedVersion);
        if (assigned) {
            assertThat(payload.path("assignedTechnician").fieldNames())
                    .toIterable()
                    .containsExactly("id", "displayName");
            assertThat(payload.path("assignedTechnician").path("id").asText())
                    .isEqualTo(NORTHSTAR_TECHNICIAN_ID.toString());
            assertThat(payload.path("assignedTechnician").path("displayName").asText())
                    .isEqualTo("Theo Technician");
        } else {
            assertThat(payload.path("assignedTechnician").isNull()).isTrue();
        }
        assertThat(payload.path("createdAt").asText()).isNotBlank();
        assertThat(payload.path("updatedAt").asText()).isNotBlank();
        assertThat(payload.path("context").fieldNames())
                .toIterable()
                .containsExactly("assetId", "assetCode", "assetName", "ruleName");
        boolean secondAsset = NORTHSTAR_ALERT_TWO_ID.equals(alertId);
        assertThat(payload.path("context").path("assetId").asText())
                .isEqualTo(
                        secondAsset
                                ? "20000000-0000-0000-0000-000000000002"
                                : "20000000-0000-0000-0000-000000000001");
        assertThat(payload.path("context").path("assetCode").asText())
                .isEqualTo(secondAsset ? "PUMP-102" : "PUMP-101");
        assertThat(payload.path("context").path("assetName").asText())
                .isEqualTo(secondAsset ? "Cooling Water Pump" : "Boiler Feed Pump");
        assertThat(payload.path("context").path("ruleName").asText())
                .isEqualTo("High bearing temperature");
    }

    private void assertSafe(JsonNode payload) {
        assertThat(payload.toString())
                .doesNotContain("organisationId")
                .doesNotContain("fingerprint")
                .doesNotContain("thresholdValue")
                .doesNotContain("telemetry")
                .doesNotContain("password")
                .doesNotContain("assignedTechnicianRoleCode");
    }

    private void assertEquivalentProblems(JsonNode first, JsonNode second) {
        for (String field : List.of("status", "code", "title", "detail")) {
            assertThat(first.path(field)).isEqualTo(second.path(field));
        }
    }

    private void assertOpenUnchanged(UUID workOrderId) {
        assertThat(assignmentState(workOrderId)).startsWith("OPEN|0|-|-|-|");
        assertThat(
                        jdbcClient
                                .sql("SELECT updated_at FROM work_order WHERE id = :workOrderId")
                                .param("workOrderId", workOrderId)
                                .query(OffsetDateTime.class)
                                .single()
                                .toInstant())
                .isEqualTo(CREATED_AT);
    }

    private String assignmentState(UUID workOrderId) {
        return jdbcClient
                .sql(
                        """
                        SELECT CONCAT_WS(
                            '|',
                            status,
                            version,
                            COALESCE(assigned_technician_user_id::text, '-'),
                            COALESCE(assigned_technician_role_code, '-'),
                            COALESCE(assigned_at::text, '-'),
                            updated_at
                        )
                        FROM work_order
                        WHERE id = :workOrderId
                        """)
                .param("workOrderId", workOrderId)
                .query(String.class)
                .single();
    }

    private void insertAlert(
            UUID alertId, UUID organisationId, UUID ruleId, String status, int fingerprintSeed) {
        Instant occurredAt = CREATED_AT.minusSeconds(3_600);
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            status,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :alertId,
                            :organisationId,
                            :ruleId,
                            :fingerprint,
                            :status,
                            :occurredAt,
                            :occurredAt,
                            :cooldownUntil,
                            :createdAt,
                            :createdAt
                        )
                        """)
                .param("alertId", alertId)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", "%064x".formatted(fingerprintSeed))
                .param("status", status)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("cooldownUntil", occurredAt.plusSeconds(300).atOffset(ZoneOffset.UTC))
                .param("createdAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertOpenWorkOrder(
            UUID workOrderId, UUID organisationId, UUID alertId, Instant createdAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO work_order (
                            id,
                            organisation_id,
                            alert_id,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :workOrderId,
                            :organisationId,
                            :alertId,
                            :createdAt,
                            :createdAt
                        )
                        """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertAssignedWorkOrder(
            UUID workOrderId,
            UUID organisationId,
            UUID alertId,
            UUID technicianUserId,
            Instant createdAt) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO work_order (
                            id,
                            organisation_id,
                            alert_id,
                            status,
                            version,
                            assigned_technician_user_id,
                            assigned_technician_role_code,
                            assigned_at,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            :workOrderId,
                            :organisationId,
                            :alertId,
                            'ASSIGNED',
                            1,
                            :technicianUserId,
                            'TECHNICIAN',
                            :assignedAt,
                            :createdAt,
                            :assignedAt
                        )
                        """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("technicianUserId", technicianUserId)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("assignedAt", createdAt.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertTestTechnician(UUID userId, UUID organisationId, int index) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO app_user (
                            id,
                            organisation_id,
                            email,
                            display_name,
                            password_hash,
                            role_code,
                            created_at,
                            updated_at
                        )
                        SELECT
                            :userId,
                            :organisationId,
                            :email,
                            :displayName,
                            password_hash,
                            'TECHNICIAN',
                            created_at,
                            updated_at
                        FROM app_user
                        WHERE id = :sourceUserId
                        """)
                .param("userId", userId)
                .param("organisationId", organisationId)
                .param("email", "workorder-test-%03d@example.test".formatted(index))
                .param("displayName", "Work-order Technician %03d".formatted(index))
                .param("sourceUserId", NORTHSTAR_TECHNICIAN_ID)
                .update();
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

    private int count(String table) {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM " + table)
                .query(Integer.class)
                .single();
    }

    private static UUID generatedAlertId(int index) {
        return UUID.fromString("81000000-0000-0000-0000-%012d".formatted(index));
    }

    private static UUID generatedWorkOrderId(int index) {
        return UUID.fromString("91000000-0000-0000-0000-%012d".formatted(index));
    }

    private static UUID generatedTechnicianId(int index) {
        return UUID.fromString("11000000-0000-0000-0001-%012d".formatted(index));
    }

    private record CsrfExchange(MockHttpSession session, String headerName, String token) {}

    private record AuthenticatedSession(
            MockHttpSession session, String csrfHeaderName, String csrfToken) {}
}
