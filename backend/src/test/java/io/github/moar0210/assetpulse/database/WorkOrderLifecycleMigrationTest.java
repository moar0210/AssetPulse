package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moar0210.assetpulse.AssetPulseApplication;
import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.identity.DatabaseUserDetailsService;
import io.github.moar0210.assetpulse.workorders.AssignWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.TransitionWorkOrderRequest;
import io.github.moar0210.assetpulse.workorders.WorkOrderDetailResponse;
import io.github.moar0210.assetpulse.workorders.WorkOrderHistoryResponse;
import io.github.moar0210.assetpulse.workorders.WorkOrderRepository;
import io.github.moar0210.assetpulse.workorders.WorkOrderService;
import io.github.moar0210.assetpulse.workorders.WorkOrderStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class WorkOrderLifecycleMigrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID FOREIGN_TECHNICIAN_ID =
            UUID.fromString("11000000-0000-0000-0000-000000000013");
    private static final UUID OPEN_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000011");
    private static final UUID ASSIGNED_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000012");
    private static final UUID FOREIGN_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000013");
    private static final Instant CREATED_AT = Instant.parse("2026-08-22T10:00:00Z");
    private static final Instant ASSIGNED_AT = CREATED_AT.plusSeconds(120);
    private static final Instant LEGACY_UPDATED_AT = CREATED_AT.plusSeconds(540);

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void v13PreservesExistingWorkOrdersBackfillsKnownAssignmentFactsAndSurvivesRestart() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("12").load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        insertV12Fixtures(jdbcClient);
        List<String> originalWorkOrders = workOrders(jdbcClient);
        List<String> finalWorkOrders;
        List<String> finalHistory;

        try (ConfigurableApplicationContext application = startApplication()) {
            assertThat(workOrders(jdbcClient)).isEqualTo(originalWorkOrders);
            WorkOrderRepository repository = application.getBean(WorkOrderRepository.class);
            assertThat(repository.findHistory(NORTHSTAR_ID, OPEN_WORK_ORDER_ID)).isEmpty();
            assertThat(repository.findHistory(NORTHSTAR_ID, ASSIGNED_WORK_ORDER_ID))
                    .containsExactly(
                            new WorkOrderHistoryResponse(
                                    1,
                                    WorkOrderStatus.OPEN,
                                    WorkOrderStatus.ASSIGNED,
                                    null,
                                    ASSIGNED_AT));
            assertThat(repository.findHistory(RIVERSIDE_ID, FOREIGN_WORK_ORDER_ID))
                    .containsExactly(
                            new WorkOrderHistoryResponse(
                                    1,
                                    WorkOrderStatus.OPEN,
                                    WorkOrderStatus.ASSIGNED,
                                    null,
                                    CREATED_AT.plusSeconds(180)));
            assertThat(repository.findHistory(NORTHSTAR_ID, FOREIGN_WORK_ORDER_ID)).isEmpty();
            assertThat(history(jdbcClient)).hasSize(2);

            WorkOrderService service = application.getBean(WorkOrderService.class);
            AuthenticatedActor technician =
                    (AuthenticatedActor)
                            application
                                    .getBean(DatabaseUserDetailsService.class)
                                    .loadUserByUsername("technician@northstar.example");
            WorkOrderDetailResponse legacy = service.detail(technician, ASSIGNED_WORK_ORDER_ID);
            assertThat(legacy.updatedAt()).isEqualTo(LEGACY_UPDATED_AT);
            assertThat(legacy.history().getFirst().transitionedAt()).isEqualTo(ASSIGNED_AT);
            assertThat(legacy.history().getFirst().actor()).isNull();
            assertThat(legacy.history().getFirst().transitionedAt()).isBefore(legacy.updatedAt());

            assertThatThrownBy(
                            () ->
                                    jdbcClient
                                            .sql(
                                                    """
                    UPDATE work_order SET assigned_at = assigned_at + INTERVAL '1 second'
                    WHERE id = :workOrderId
                    """)
                                            .param("workOrderId", ASSIGNED_WORK_ORDER_ID)
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
            WorkOrderDetailResponse started =
                    service.start(
                            technician, ASSIGNED_WORK_ORDER_ID, new TransitionWorkOrderRequest(1L));
            assertThat(started.status()).isEqualTo(WorkOrderStatus.IN_PROGRESS);
            assertThat(started.version()).isEqualTo(2);
            assertThat(started.history()).hasSize(2);
            assertThat(started.history().getFirst()).isEqualTo(legacy.history().getFirst());
            assertThat(started.history().get(1).actor().id()).isEqualTo(TECHNICIAN_ID);
            assertThat(started.history().get(1).transitionedAt()).isEqualTo(started.updatedAt());
            WorkOrderDetailResponse completed =
                    service.complete(
                            technician, ASSIGNED_WORK_ORDER_ID, new TransitionWorkOrderRequest(2L));
            assertThat(completed.status()).isEqualTo(WorkOrderStatus.DONE);
            assertThat(completed.version()).isEqualTo(3);
            assertThat(completed.history()).hasSize(3);
            assertThat(completed.history().getFirst()).isEqualTo(legacy.history().getFirst());
            assertThat(completed.history().get(2).actor().id()).isEqualTo(TECHNICIAN_ID);

            WorkOrderDetailResponse newAssignment =
                    service.assign(
                            NORTHSTAR_ID,
                            ADMIN_ID,
                            OPEN_WORK_ORDER_ID,
                            new AssignWorkOrderRequest(TECHNICIAN_ID, 0L));
            assertThat(newAssignment.history()).hasSize(1);
            assertThat(newAssignment.history().getFirst().actor().id()).isEqualTo(ADMIN_ID);
            assertThat(newAssignment.history().getFirst().transitionedAt())
                    .isEqualTo(newAssignment.updatedAt());
            finalWorkOrders = workOrders(jdbcClient);
            finalHistory = history(jdbcClient);
        }

        try (ConfigurableApplicationContext restarted = startApplication()) {
            assertThat(workOrders(jdbcClient)).isEqualTo(finalWorkOrders);
            assertThat(history(jdbcClient)).isEqualTo(finalHistory);
            assertThat(finalHistory).hasSize(5);
            assertThat(
                            restarted
                                    .getBean(WorkOrderRepository.class)
                                    .findHistory(NORTHSTAR_ID, ASSIGNED_WORK_ORDER_ID)
                                    .getFirst()
                                    .actor())
                    .isNull();
            assertThat(
                            jdbcClient
                                    .sql(
                                            "SELECT COUNT(*)::integer FROM flyway_schema_history WHERE success AND version = '13'")
                                    .query(Integer.class)
                                    .single())
                    .isOne();
            assertThat(
                            jdbcClient
                                    .sql(
                                            "SELECT COUNT(*)::integer FROM work_order_status_history WHERE actor_user_id IS NULL")
                                    .query(Integer.class)
                                    .single())
                    .isEqualTo(2);
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(AssetPulseApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                        "--spring.datasource.password=" + POSTGRESQL.getPassword());
    }

    private void insertV12Fixtures(JdbcClient jdbcClient) {
        jdbcClient
                .sql(
                        """
                INSERT INTO app_user (id, organisation_id, email, display_name, password_hash, role_code, created_at, updated_at)
                SELECT :userId, :organisationId, 'legacy-technician@riverside.example', 'Legacy Technician',
                    password_hash, role_code, created_at, updated_at
                FROM app_user WHERE id = :sourceUserId
                """)
                .param("userId", FOREIGN_TECHNICIAN_ID)
                .param("organisationId", RIVERSIDE_ID)
                .param("sourceUserId", TECHNICIAN_ID)
                .update();
        UUID openAlertId = UUID.fromString("80000000-0000-0000-0000-000000000011");
        UUID assignedAlertId = UUID.fromString("80000000-0000-0000-0000-000000000012");
        UUID foreignAlertId = UUID.fromString("80000000-0000-0000-0000-000000000013");
        insertAlert(
                jdbcClient,
                openAlertId,
                NORTHSTAR_ID,
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                1);
        insertAlert(
                jdbcClient,
                assignedAlertId,
                NORTHSTAR_ID,
                UUID.fromString("40000000-0000-0000-0000-000000000002"),
                2);
        insertAlert(
                jdbcClient,
                foreignAlertId,
                RIVERSIDE_ID,
                UUID.fromString("40000000-0000-0000-0000-000000000003"),
                3);
        insertWorkOrder(
                jdbcClient,
                NORTHSTAR_ID,
                OPEN_WORK_ORDER_ID,
                openAlertId,
                null,
                null,
                CREATED_AT.plusSeconds(60));
        insertWorkOrder(
                jdbcClient,
                NORTHSTAR_ID,
                ASSIGNED_WORK_ORDER_ID,
                assignedAlertId,
                TECHNICIAN_ID,
                ASSIGNED_AT,
                LEGACY_UPDATED_AT);
        insertWorkOrder(
                jdbcClient,
                RIVERSIDE_ID,
                FOREIGN_WORK_ORDER_ID,
                foreignAlertId,
                FOREIGN_TECHNICIAN_ID,
                CREATED_AT.plusSeconds(180),
                CREATED_AT.plusSeconds(180));
    }

    private void insertAlert(
            JdbcClient jdbcClient, UUID alertId, UUID organisationId, UUID ruleId, int seed) {
        jdbcClient
                .sql(
                        """
                INSERT INTO alert (
                    id, organisation_id, threshold_rule_id, fingerprint,
                    first_occurred_at, last_occurred_at, cooldown_until, created_at, updated_at
                ) VALUES (:alertId, :organisationId, :ruleId, :fingerprint,
                    '2026-08-22 09:00:00+00', '2026-08-22 09:00:00+00', '2026-08-22 09:05:00+00',
                    '2026-08-22 09:00:00+00', '2026-08-22 09:00:00+00')
                """)
                .param("alertId", alertId)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", "%064x".formatted(seed))
                .update();
    }

    private void insertWorkOrder(
            JdbcClient jdbcClient,
            UUID organisationId,
            UUID workOrderId,
            UUID alertId,
            UUID technicianId,
            Instant assignedAt,
            Instant updatedAt) {
        jdbcClient
                .sql(
                        """
                INSERT INTO work_order (
                    id, organisation_id, alert_id, status, version, assigned_technician_user_id,
                    assigned_technician_role_code, assigned_at, created_at, updated_at
                ) VALUES (:workOrderId, :organisationId, :alertId, :status, :version, :technicianId,
                    :technicianRoleCode, :assignedAt, :createdAt, :updatedAt)
                """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .param("status", technicianId == null ? "OPEN" : "ASSIGNED")
                .param("version", technicianId == null ? 0 : 1)
                .param("technicianId", technicianId)
                .param("technicianRoleCode", technicianId == null ? null : "TECHNICIAN")
                .param(
                        "assignedAt",
                        assignedAt == null ? null : assignedAt.atOffset(ZoneOffset.UTC))
                .param("createdAt", CREATED_AT.atOffset(ZoneOffset.UTC))
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private List<String> workOrders(JdbcClient jdbcClient) {
        return jdbcClient
                .sql("SELECT row_to_json(work_order)::text FROM work_order ORDER BY id")
                .query(String.class)
                .list();
    }

    private List<String> history(JdbcClient jdbcClient) {
        return jdbcClient
                .sql(
                        """
                SELECT row_to_json(work_order_status_history)::text FROM work_order_status_history
                ORDER BY organisation_id, work_order_id, sequence_number
                """)
                .query(String.class)
                .list();
    }
}
