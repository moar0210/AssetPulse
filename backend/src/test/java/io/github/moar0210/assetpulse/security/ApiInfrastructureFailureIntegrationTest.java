package io.github.moar0210.assetpulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ASSETPULSE_SESSION_COOKIE_SECURE=false",
            "spring.datasource.hikari.connection-timeout=1000",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.maximum-pool-size=2"
        })
@DirtiesContext
@Testcontainers
class ApiInfrastructureFailureIntegrationTest {

    private static final String SESSION_PATH = "/api/v1/session";
    private static final String WORK_ORDERS_PATH = "/api/v1/work-orders";
    private static final String AUDIT_CONSTRAINT = "ck_http_test_work_order_audit";
    private static final UUID ALERT_ID = UUID.fromString("80000000-0000-0000-0000-000000000148");
    private static final String TELEMETRY_PATH =
            "/api/v1/sensors/30000000-0000-0000-0000-000000000001/telemetry-readings"
                    + "?from=2026-09-01T00:00:00Z&to=2026-09-02T00:00:00Z";

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"))
                    .withUrlParam("connectTimeout", "2")
                    .withUrlParam("socketTimeout", "2");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private HikariDataSource datasource;

    @BeforeEach
    void clearFixtures() {
        jdbcClient.sql("TRUNCATE TABLE audit_event").update();
        jdbcClient.sql("TRUNCATE TABLE work_order_status_history").update();
        jdbcClient.sql("DELETE FROM work_order").update();
        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient.sql("DELETE FROM alert").update();
    }

    @Test
    @DisplayName(
            "WO-01, WO-05, AUD-01: real HTTP audit failures return safe problems and roll back")
    void auditWriteFailurePreservesAtomicWorkOrderStateAndAllowsExplicitRecovery()
            throws Exception {
        insertAlert();
        AuthenticatedSession admin = login();
        List<String> beforeAudit = rows("audit_event");
        HttpResponse<String> failedCreate;
        rejectWorkOrderAudit();
        try {
            failedCreate = post(admin, WORK_ORDERS_PATH, Map.of("alertId", ALERT_ID));
            assertThat(rows("work_order")).isEmpty();
            assertThat(rows("work_order_status_history")).isEmpty();
            assertThat(rows("audit_event")).isEqualTo(beforeAudit);
        } finally {
            restoreWorkOrderAudit();
        }
        HttpResponse<String> created = post(admin, WORK_ORDERS_PATH, Map.of("alertId", ALERT_ID));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID workOrderId =
                UUID.fromString(objectMapper.readTree(created.body()).path("id").asText());
        List<String> beforeWorkOrder = rows("work_order");
        List<String> beforeAssignmentAudit = rows("audit_event");
        String assignPath = WORK_ORDERS_PATH + "/" + workOrderId + "/assign";
        HttpResponse<String> failedAssignment;
        rejectWorkOrderAudit();
        try {
            failedAssignment =
                    post(
                            admin,
                            assignPath,
                            Map.of(
                                    "technicianUserId",
                                    "10000000-0000-0000-0000-000000000002",
                                    "expectedVersion",
                                    0));
            assertThat(rows("work_order")).isEqualTo(beforeWorkOrder);
            assertThat(rows("work_order_status_history")).isEmpty();
            assertThat(rows("audit_event")).isEqualTo(beforeAssignmentAudit);
        } finally {
            restoreWorkOrderAudit();
        }
        HttpResponse<String> recovered = get(admin.client(), WORK_ORDERS_PATH + "/" + workOrderId);
        assertThat(recovered.statusCode()).isEqualTo(200);
        JsonNode current = objectMapper.readTree(recovered.body());
        assertThat(current.path("status").asText()).isEqualTo("OPEN");
        assertThat(current.path("version").asInt()).isZero();
        assertThat(current.path("history")).isEmpty();
        assertAll(
                () -> assertSafeInfrastructureProblem(failedCreate, WORK_ORDERS_PATH),
                () -> assertSafeInfrastructureProblem(failedAssignment, assignPath));
    }

    @Test
    @DisplayName("AUTH-01, AUTH-03: actual database outage returns safe problems and recovers")
    void databaseConnectionOutageReturnsSafeProblemsAndRecoversAcrossReadRoutes() throws Exception {
        AuthenticatedSession admin = login();
        List<String> paths =
                List.of("/api/v1/assets", WORK_ORDERS_PATH, "/api/v1/alerts", TELEMETRY_PATH);
        for (String path : paths) {
            assertThat(get(admin.client(), path).statusCode()).isEqualTo(200);
        }

        Map<String, HttpResponse<String>> failures = new LinkedHashMap<>();
        POSTGRESQL.getDockerClient().pauseContainerCmd(POSTGRESQL.getContainerId()).exec();
        try {
            datasource.getHikariPoolMXBean().softEvictConnections();
            for (String path : paths) {
                failures.put(path, get(admin.client(), path));
            }
        } finally {
            POSTGRESQL.getDockerClient().unpauseContainerCmd(POSTGRESQL.getContainerId()).exec();
            datasource.getHikariPoolMXBean().softEvictConnections();
        }

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            for (String path : paths) {
                                assertThat(get(admin.client(), path).statusCode()).isEqualTo(200);
                            }
                        });
        assertAll(
                failures.entrySet().stream()
                        .map(
                                failure ->
                                        () ->
                                                assertSafeInfrastructureProblem(
                                                        failure.getValue(),
                                                        URI.create(failure.getKey()).getPath())));
    }

    private void assertSafeInfrastructureProblem(HttpResponse<String> response, String path)
            throws Exception {
        assertThat(response.statusCode())
                .as(
                        "HTTP problem response: Content-Type=%s, Cache-Control=%s, X-Correlation-ID=%s, body=%s",
                        response.headers().firstValue("Content-Type").orElse("absent"),
                        response.headers().firstValue("Cache-Control").orElse("absent"),
                        response.headers().firstValue("X-Correlation-ID").orElse("absent"),
                        response.body())
                .isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                .contains("no-store");
        JsonNode problem = objectMapper.readTree(response.body());
        List<String> fields = new ArrayList<>();
        problem.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactlyInAnyOrder(
                        "type", "title", "status", "detail", "instance", "code", "correlationId");
        assertThat(problem.path("status").asInt()).isEqualTo(503);
        assertThat(problem.path("instance").asText()).isEqualTo(path);
        assertThat(problem.path("type").asText())
                .isEqualTo("urn:assetpulse:problem:api-unavailable");
        assertThat(problem.path("title").asText()).isEqualTo("API unavailable");
        assertThat(problem.path("detail").asText())
                .isEqualTo(
                        "The request outcome could not be confirmed. Refresh the current state before retrying.");
        assertThat(problem.path("code").asText()).isEqualTo("API_UNAVAILABLE");
        UUID correlationId = UUID.fromString(problem.path("correlationId").asText());
        assertThat(response.headers().firstValue("X-Correlation-ID").orElseThrow())
                .isEqualTo(correlationId.toString());
        assertThat(response.body())
                .doesNotContain(
                        "audit_event",
                        AUDIT_CONSTRAINT,
                        "SQLException",
                        "stackTrace",
                        "jdbc:",
                        "AssetPulse1!",
                        "admin@northstar.example");
    }

    private AuthenticatedSession login() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(cookies)
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        HttpResponse<String> anonymousCsrf = get(client, SESSION_PATH + "/csrf");
        assertThat(anonymousCsrf.statusCode()).isEqualTo(200);
        JsonNode first = objectMapper.readTree(anonymousCsrf.body());
        String originalSession =
                cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("ASSETPULSE_SESSION"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        AuthenticatedSession anonymous =
                new AuthenticatedSession(
                        client, first.path("headerName").asText(), first.path("token").asText());
        HttpResponse<String> login =
                post(
                        anonymous,
                        SESSION_PATH,
                        Map.of("email", "admin@northstar.example", "password", "AssetPulse1!"));
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(
                        cookies.getCookieStore().getCookies().stream()
                                .filter(cookie -> cookie.getName().equals("ASSETPULSE_SESSION"))
                                .findFirst()
                                .orElseThrow()
                                .getValue())
                .isNotEqualTo(originalSession);
        HttpResponse<String> authenticatedCsrf = get(client, SESSION_PATH + "/csrf");
        assertThat(authenticatedCsrf.statusCode()).isEqualTo(200);
        JsonNode rotated = objectMapper.readTree(authenticatedCsrf.body());
        assertThat(rotated.path("token").asText()).isNotEqualTo(first.path("token").asText());
        return new AuthenticatedSession(
                client, rotated.path("headerName").asText(), rotated.path("token").asText());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(
            AuthenticatedSession session, String path, Map<String, ?> body) throws Exception {
        return session.client()
                .send(
                        request(path)
                                .header("Content-Type", "application/json")
                                .header(session.csrfHeader(), session.csrfToken())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                objectMapper.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
    }

    private List<String> rows(String table) {
        return jdbcClient
                .sql("SELECT row_to_json(" + table + ")::text FROM " + table + " ORDER BY 1")
                .query(String.class)
                .list();
    }

    private void rejectWorkOrderAudit() {
        jdbcClient
                .sql(
                        "ALTER TABLE audit_event ADD CONSTRAINT "
                                + AUDIT_CONSTRAINT
                                + " CHECK (subject_work_order_id IS NULL) NOT VALID")
                .update();
    }

    private void restoreWorkOrderAudit() {
        jdbcClient.sql("ALTER TABLE audit_event DROP CONSTRAINT " + AUDIT_CONSTRAINT).update();
    }

    private void insertAlert() {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (id, organisation_id, threshold_rule_id, fingerprint,
                            status, first_occurred_at, last_occurred_at, cooldown_until,
                            created_at, updated_at)
                        VALUES (:alertId, '00000000-0000-0000-0000-000000000001',
                            '40000000-0000-0000-0000-000000000001', :fingerprint,
                            'OPEN', '2026-08-23 10:00:00+00', '2026-08-23 10:00:00+00',
                            '2026-08-23 10:05:00+00', '2026-08-23 10:00:00+00',
                            '2026-08-23 10:00:00+00')
                        """)
                .param("alertId", ALERT_ID)
                .param("fingerprint", "%064x".formatted(148))
                .update();
    }

    private record AuthenticatedSession(HttpClient client, String csrfHeader, String csrfToken) {}
}
