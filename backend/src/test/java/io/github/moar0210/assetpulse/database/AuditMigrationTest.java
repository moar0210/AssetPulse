package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AuditMigrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ADMIN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ADMIN_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID NORTHSTAR_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000141");
    private static final UUID RIVERSIDE_ALERT_ID =
            UUID.fromString("80000000-0000-0000-0000-000000000142");
    private static final UUID NORTHSTAR_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000141");
    private static final UUID RIVERSIDE_WORK_ORDER_ID =
            UUID.fromString("90000000-0000-0000-0000-000000000142");
    private static final UUID NORTHSTAR_PROCESSING_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000141");
    private static final UUID RIVERSIDE_PROCESSING_EVENT_ID =
            UUID.fromString("70000000-0000-0000-0000-000000000142");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void v14AddsConstrainedImmutableAuditStorageWithoutBackfill() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        POSTGRESQL.getJdbcUrl(),
                        POSTGRESQL.getUsername(),
                        POSTGRESQL.getPassword());
        Flyway.configure().dataSource(dataSource).target("13").load().migrate();
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        insertSubjectFixtures(
                jdbcClient,
                NORTHSTAR_ID,
                UUID.fromString("40000000-0000-0000-0000-000000000001"),
                NORTHSTAR_ALERT_ID,
                NORTHSTAR_WORK_ORDER_ID,
                NORTHSTAR_PROCESSING_EVENT_ID);
        insertSubjectFixtures(
                jdbcClient,
                RIVERSIDE_ID,
                UUID.fromString("40000000-0000-0000-0000-000000000003"),
                RIVERSIDE_ALERT_ID,
                RIVERSIDE_WORK_ORDER_ID,
                RIVERSIDE_PROCESSING_EVENT_ID);
        Map<String, List<String>> originalDomain = domainSnapshot(jdbcClient);
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        assertThat(domainSnapshot(jdbcClient)).isEqualTo(originalDomain);

        assertThat(count(jdbcClient, "SELECT COUNT(*)::integer FROM audit_event")).isZero();
        insertAuthenticationSuccess(
                jdbcClient, UUID.randomUUID(), NORTHSTAR_ID, ADMIN_ID, ADMIN_ID);
        insertAuthenticationFailure(jdbcClient, UUID.randomUUID());
        assertThat(count(jdbcClient, "SELECT COUNT(*)::integer FROM audit_event")).isEqualTo(2);
        assertThat(
                        count(
                                jdbcClient,
                                "SELECT COUNT(*)::integer FROM audit_event WHERE organisation_id IS NULL"))
                .isOne();

        for (AuditShape shape :
                List.of(
                        new AuditShape(
                                "ALERT_ACKNOWLEDGED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                NORTHSTAR_ALERT_ID,
                                null,
                                null),
                        new AuditShape(
                                "ALERT_ACKNOWLEDGED",
                                RIVERSIDE_ID,
                                RIVERSIDE_ADMIN_ID,
                                null,
                                RIVERSIDE_ALERT_ID,
                                null,
                                null),
                        new AuditShape(
                                "WORK_ORDER_CREATED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                null,
                                NORTHSTAR_WORK_ORDER_ID,
                                null),
                        new AuditShape(
                                "WORK_ORDER_CREATED",
                                RIVERSIDE_ID,
                                RIVERSIDE_ADMIN_ID,
                                null,
                                null,
                                RIVERSIDE_WORK_ORDER_ID,
                                null),
                        new AuditShape(
                                "PROCESSING_EVENT_RETRIED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                null,
                                null,
                                NORTHSTAR_PROCESSING_EVENT_ID),
                        new AuditShape(
                                "PROCESSING_EVENT_RETRIED",
                                RIVERSIDE_ID,
                                RIVERSIDE_ADMIN_ID,
                                null,
                                null,
                                null,
                                RIVERSIDE_PROCESSING_EVENT_ID))) {
            insertAuditShape(jdbcClient, shape);
        }
        List<String> expectedAudit = snapshot(jdbcClient, "audit_event");
        assertThat(expectedAudit).hasSize(8);

        assertForeignSubjectRejected(
                jdbcClient,
                new AuditShape(
                        "ALERT_ACKNOWLEDGED",
                        NORTHSTAR_ID,
                        ADMIN_ID,
                        null,
                        RIVERSIDE_ALERT_ID,
                        null,
                        null),
                "fk_audit_event_subject_alert");
        assertForeignSubjectRejected(
                jdbcClient,
                new AuditShape(
                        "WORK_ORDER_CREATED",
                        NORTHSTAR_ID,
                        ADMIN_ID,
                        null,
                        null,
                        RIVERSIDE_WORK_ORDER_ID,
                        null),
                "fk_audit_event_subject_work_order");
        assertForeignSubjectRejected(
                jdbcClient,
                new AuditShape(
                        "PROCESSING_EVENT_RETRIED",
                        NORTHSTAR_ID,
                        ADMIN_ID,
                        null,
                        null,
                        null,
                        RIVERSIDE_PROCESSING_EVENT_ID),
                "fk_audit_event_subject_processing_event");

        assertThatThrownBy(
                        () ->
                                insertAuthenticationSuccess(
                                        jdbcClient,
                                        UUID.randomUUID(),
                                        NORTHSTAR_ID,
                                        ADMIN_ID,
                                        RIVERSIDE_ADMIN_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertAuthenticationSuccess(
                                        jdbcClient,
                                        UUID.randomUUID(),
                                        RIVERSIDE_ID,
                                        ADMIN_ID,
                                        ADMIN_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                        INSERT INTO audit_event (id, action, correlation_id)
                        VALUES (:id, 'AUTHENTICATION_SUCCEEDED', :correlationId)
                        """)
                                        .param("id", UUID.randomUUID())
                                        .param("correlationId", UUID.randomUUID())
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        for (AuditShape shape :
                List.of(
                        new AuditShape(
                                "AUTHENTICATION_FAILED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                null,
                                null,
                                null),
                        new AuditShape(
                                "AUTHENTICATION_FAILED", null, ADMIN_ID, null, null, null, null),
                        new AuditShape(
                                "AUTHENTICATION_FAILED", null, null, ADMIN_ID, null, null, null),
                        new AuditShape(
                                "AUTHENTICATION_SUCCEEDED",
                                null,
                                ADMIN_ID,
                                ADMIN_ID,
                                null,
                                null,
                                null),
                        new AuditShape(
                                "AUTHENTICATION_SUCCEEDED",
                                NORTHSTAR_ID,
                                null,
                                ADMIN_ID,
                                null,
                                null,
                                null),
                        new AuditShape(
                                "ALERT_ACKNOWLEDGED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                null,
                                null,
                                null),
                        new AuditShape(
                                "ALERT_ACKNOWLEDGED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                ADMIN_ID,
                                null,
                                null,
                                null),
                        new AuditShape(
                                "WORK_ORDER_CREATED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                NORTHSTAR_ALERT_ID,
                                null,
                                null),
                        new AuditShape(
                                "PROCESSING_EVENT_RETRIED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                null,
                                NORTHSTAR_WORK_ORDER_ID,
                                null),
                        new AuditShape(
                                "ALERT_ACKNOWLEDGED",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                ADMIN_ID,
                                NORTHSTAR_ALERT_ID,
                                null,
                                null),
                        new AuditShape(
                                "UNKNOWN_ACTION",
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                NORTHSTAR_ALERT_ID,
                                null,
                                null),
                        new AuditShape(
                                null,
                                NORTHSTAR_ID,
                                ADMIN_ID,
                                null,
                                NORTHSTAR_ALERT_ID,
                                null,
                                null))) {
            assertThatThrownBy(() -> insertAuditShape(jdbcClient, shape))
                    .as("Rejected audit shape: %s", shape)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String occurredAt : List.of("infinity", "-infinity")) {
            assertThatThrownBy(
                            () ->
                                    jdbcClient
                                            .sql(
                                                    """
                    INSERT INTO audit_event (id, action, occurred_at, correlation_id)
                    VALUES (:id, 'AUTHENTICATION_FAILED', CAST(:occurredAt AS TIMESTAMP WITH TIME ZONE), :correlationId)
                    """)
                                            .param("id", UUID.randomUUID())
                                            .param("occurredAt", occurredAt)
                                            .param("correlationId", UUID.randomUUID())
                                            .update())
                    .as("Rejected audit timestamp: %s", occurredAt)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                "UPDATE audit_event SET occurred_at = occurred_at + INTERVAL '1 second'")
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM audit_event").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(snapshot(jdbcClient, "audit_event")).isEqualTo(expectedAudit);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                        SELECT indexdef FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname = 'ix_audit_event_organisation_time_id'
                        """)
                                .query(String.class)
                                .single())
                .contains("organisation_id", "occurred_at DESC", "id DESC");
        assertThat(
                        count(
                                jdbcClient,
                                "SELECT COUNT(*)::integer FROM flyway_schema_history WHERE success AND version = '14'"))
                .isOne();

        flyway.migrate();
        assertThat(snapshot(jdbcClient, "audit_event")).isEqualTo(expectedAudit);
        assertThat(domainSnapshot(jdbcClient)).isEqualTo(originalDomain);
        assertThat(
                        count(
                                jdbcClient,
                                "SELECT COUNT(*)::integer FROM flyway_schema_history WHERE success AND version = '14'"))
                .isOne();
    }

    private static void insertSubjectFixtures(
            JdbcClient jdbcClient,
            UUID organisationId,
            UUID ruleId,
            UUID alertId,
            UUID workOrderId,
            UUID processingEventId) {
        jdbcClient
                .sql(
                        """
                INSERT INTO alert (
                    id, organisation_id, threshold_rule_id, fingerprint,
                    first_occurred_at, last_occurred_at, cooldown_until, created_at, updated_at
                ) VALUES (
                    :alertId, :organisationId, :ruleId, :fingerprint,
                    '2026-08-27 10:00:00+00', '2026-08-27 10:00:00+00', '2026-08-27 10:05:00+00',
                    '2026-08-27 10:00:00+00', '2026-08-27 10:00:00+00'
                )
                """)
                .param("alertId", alertId)
                .param("organisationId", organisationId)
                .param("ruleId", ruleId)
                .param("fingerprint", "a".repeat(64))
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO work_order (id, organisation_id, alert_id)
                VALUES (:workOrderId, :organisationId, :alertId)
                """)
                .param("workOrderId", workOrderId)
                .param("organisationId", organisationId)
                .param("alertId", alertId)
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO telemetry_batch (
                    id, organisation_id, idempotency_key, request_fingerprint, reading_count, accepted_at
                ) VALUES (
                    :batchId, :organisationId, 'audit-migration-fixture', :fingerprint, 1,
                    '2026-08-27 10:00:00+00'
                )
                """)
                .param("batchId", processingEventId)
                .param("organisationId", organisationId)
                .param("fingerprint", "a".repeat(64))
                .update();
        jdbcClient
                .sql(
                        """
                INSERT INTO telemetry_processing_event (
                    id, organisation_id, telemetry_batch_id, event_type, created_at
                ) VALUES (
                    :eventId, :organisationId, :eventId, 'TELEMETRY_BATCH_ACCEPTED',
                    '2026-08-27 10:00:00+00'
                )
                """)
                .param("eventId", processingEventId)
                .param("organisationId", organisationId)
                .update();
    }

    private static void insertAuditShape(JdbcClient jdbcClient, AuditShape shape) {
        jdbcClient
                .sql(
                        """
                INSERT INTO audit_event (
                    id, organisation_id, actor_user_id, action,
                    subject_user_id, subject_alert_id, subject_work_order_id,
                    subject_processing_event_id, correlation_id
                ) VALUES (
                    :id, :organisationId, :actorUserId, :action,
                    :subjectUserId, :subjectAlertId, :subjectWorkOrderId,
                    :subjectProcessingEventId, :correlationId
                )
                """)
                .param("id", UUID.randomUUID())
                .param("organisationId", shape.organisationId())
                .param("actorUserId", shape.actorUserId())
                .param("action", shape.action())
                .param("subjectUserId", shape.subjectUserId())
                .param("subjectAlertId", shape.subjectAlertId())
                .param("subjectWorkOrderId", shape.subjectWorkOrderId())
                .param("subjectProcessingEventId", shape.subjectProcessingEventId())
                .param("correlationId", UUID.randomUUID())
                .update();
    }

    private static void assertForeignSubjectRejected(
            JdbcClient jdbcClient, AuditShape shape, String constraintName) {
        assertThatThrownBy(() -> insertAuditShape(jdbcClient, shape))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraintName);
    }

    private static Map<String, List<String>> domainSnapshot(JdbcClient jdbcClient) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table :
                List.of(
                        "app_user",
                        "alert",
                        "work_order",
                        "telemetry_batch",
                        "telemetry_processing_event")) {
            result.put(table, snapshot(jdbcClient, table));
        }
        return result;
    }

    private static List<String> snapshot(JdbcClient jdbcClient, String table) {
        return jdbcClient
                .sql(
                        "SELECT row_to_json(snapshot_row)::text FROM %s snapshot_row ORDER BY id"
                                .formatted(table))
                .query(String.class)
                .list();
    }

    private static void insertAuthenticationSuccess(
            JdbcClient jdbcClient,
            UUID id,
            UUID organisationId,
            UUID actorUserId,
            UUID subjectUserId) {
        jdbcClient
                .sql(
                        """
                INSERT INTO audit_event (
                    id, organisation_id, actor_user_id, action, subject_user_id, correlation_id
                ) VALUES (
                    :id, :organisationId, :actorUserId,
                    'AUTHENTICATION_SUCCEEDED', :subjectUserId, :correlationId
                )
                """)
                .param("id", id)
                .param("organisationId", organisationId)
                .param("actorUserId", actorUserId)
                .param("subjectUserId", subjectUserId)
                .param("correlationId", UUID.randomUUID())
                .update();
    }

    private static void insertAuthenticationFailure(JdbcClient jdbcClient, UUID id) {
        jdbcClient
                .sql(
                        """
                INSERT INTO audit_event (id, action, correlation_id)
                VALUES (:id, 'AUTHENTICATION_FAILED', :correlationId)
                """)
                .param("id", id)
                .param("correlationId", UUID.randomUUID())
                .update();
    }

    private static int count(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private record AuditShape(
            String action,
            UUID organisationId,
            UUID actorUserId,
            UUID subjectUserId,
            UUID subjectAlertId,
            UUID subjectWorkOrderId,
            UUID subjectProcessingEventId) {}
}
