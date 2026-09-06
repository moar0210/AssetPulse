package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moar0210.assetpulse.AssetPulseApplication;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DatabaseMigrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void migrationsCreateDeterministicPostgresqlStateAcrossRestart() {
        DatabaseState firstState;
        List<String> firstSeedRows;

        try (ConfigurableApplicationContext firstApplication = startApplication()) {
            JdbcClient jdbcClient = firstApplication.getBean(JdbcClient.class);

            assertThat(jdbcClient.sql("SELECT version()").query(String.class).single())
                    .startsWith("PostgreSQL 17.10");
            assertSeedRelationships(jdbcClient);
            assertProcessingEventLifecycleSchema(jdbcClient);
            assertAlertSchema(jdbcClient);
            assertWorkOrderSchema(jdbcClient);
            assertWorkOrderHistorySchema(jdbcClient);
            assertDatabaseConstraints(jdbcClient);
            assertAlertHistoryConstraints(jdbcClient);
            assertWorkOrderConstraints(jdbcClient);
            firstState = readState(jdbcClient);
            firstSeedRows = readSeedRows(jdbcClient);
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication()) {
            JdbcClient jdbcClient = restartedApplication.getBean(JdbcClient.class);

            assertThat(readState(jdbcClient)).isEqualTo(firstState);
            assertThat(readSeedRows(jdbcClient)).containsExactlyElementsOf(firstSeedRows);
        }

        assertThat(firstState)
                .isEqualTo(new DatabaseState("16", 16, 2, 3, 4, 3, 3, 3, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(firstSeedRows).hasSize(18);
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

    private DatabaseState readState(JdbcClient jdbcClient) {
        return new DatabaseState(
                jdbcClient
                        .sql(
                                """
                                SELECT version
                                FROM flyway_schema_history
                                WHERE success
                                ORDER BY installed_rank DESC
                                LIMIT 1
                                """)
                        .query(String.class)
                        .single(),
                count(
                        jdbcClient,
                        "SELECT COUNT(*)::integer FROM flyway_schema_history WHERE success"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM organisation"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM app_role"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM app_user"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM asset"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM sensor"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM threshold_rule"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM telemetry_batch"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM telemetry_reading"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM telemetry_processing_event"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM alert"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM alert_status_history"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM work_order"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM work_order_status_history"),
                count(jdbcClient, "SELECT COUNT(*)::integer FROM audit_event"));
    }

    private List<String> readSeedRows(JdbcClient jdbcClient) {
        return jdbcClient
                .sql(
                        """
                        SELECT seed_row
                        FROM (
                            SELECT CONCAT_WS('|', 'organisation', id, slug, name, created_at, updated_at) AS seed_row
                            FROM organisation
                            UNION ALL
                            SELECT CONCAT_WS('|', 'app_role', code, display_name, created_at, updated_at)
                            FROM app_role
                            UNION ALL
                            SELECT CONCAT_WS(
                                '|',
                                'app_user',
                                id,
                                organisation_id,
                                email,
                                display_name,
                                password_hash,
                                role_code,
                                created_at,
                                updated_at
                            )
                            FROM app_user
                            UNION ALL
                            SELECT CONCAT_WS(
                                '|',
                                'asset',
                                id,
                                organisation_id,
                                asset_code,
                                name,
                                created_at,
                                updated_at
                            )
                            FROM asset
                            UNION ALL
                            SELECT CONCAT_WS(
                                '|',
                                'sensor',
                                id,
                                organisation_id,
                                asset_id,
                                sensor_key,
                                name,
                                measurement_type,
                                unit,
                                created_at,
                                updated_at
                            )
                            FROM sensor
                            UNION ALL
                            SELECT CONCAT_WS(
                                '|',
                                'threshold_rule',
                                id,
                                organisation_id,
                                sensor_id,
                                rule_code,
                                name,
                                comparison,
                                threshold_value,
                                cooldown_seconds,
                                enabled,
                                created_at,
                                updated_at
                            )
                            FROM threshold_rule
                        ) seeded_state
                        ORDER BY seed_row
                        """)
                .query(String.class)
                .list();
    }

    private void assertSeedRelationships(JdbcClient jdbcClient) {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM app_user u
                                        JOIN organisation o ON o.id = u.organisation_id
                                        JOIN app_role r ON r.code = u.role_code
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(4);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM sensor s
                                        JOIN asset a
                                          ON a.organisation_id = s.organisation_id
                                         AND a.id = s.asset_id
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(3);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM threshold_rule r
                                        JOIN sensor s
                                          ON s.organisation_id = r.organisation_id
                                         AND s.id = r.sensor_id
                                        WHERE r.enabled
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(3);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(DISTINCT u.role_code)::integer
                                        FROM app_user u
                                        JOIN organisation o ON o.id = u.organisation_id
                                        WHERE o.slug = 'northstar-operations'
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(3);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM asset a
                                        JOIN organisation o ON o.id = a.organisation_id
                                        WHERE o.slug = 'northstar-operations'
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(2);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM app_user
                                        WHERE password_hash LIKE '{bcrypt}$2b$12$%'
                                        """)
                                .query(Integer.class)
                                .single())
                .isEqualTo(4);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND indexname = 'ix_asset_organisation_name_id'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND indexname = 'ix_telemetry_reading_organisation_sensor_observed_id'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();
    }

    private void assertProcessingEventLifecycleSchema(JdbcClient jdbcClient) {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS(
                                            '|',
                                            column_name,
                                            data_type,
                                            COALESCE(character_maximum_length::text, '-'),
                                            is_nullable,
                                            (column_default IS NOT NULL)::text
                                        )
                                        FROM information_schema.columns
                                        WHERE table_schema = 'public'
                                          AND table_name = 'telemetry_processing_event'
                                          AND column_name IN (
                                              'status',
                                              'attempt_count',
                                              'next_attempt_at',
                                              'claim_token',
                                              'claim_owner',
                                              'lease_expires_at',
                                              'completed_at',
                                              'dead_at',
                                              'last_error_code',
                                              'last_error_message',
                                              'updated_at'
                                          )
                                        ORDER BY column_name
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "attempt_count|smallint|-|NO|true",
                        "claim_owner|character varying|128|YES|false",
                        "claim_token|uuid|-|YES|false",
                        "completed_at|timestamp with time zone|-|YES|false",
                        "dead_at|timestamp with time zone|-|YES|false",
                        "last_error_code|character varying|64|YES|false",
                        "last_error_message|character varying|256|YES|false",
                        "lease_expires_at|timestamp with time zone|-|YES|false",
                        "next_attempt_at|timestamp with time zone|-|YES|true",
                        "status|character varying|16|NO|true",
                        "updated_at|timestamp with time zone|-|NO|true");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT indexname
                                        FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND tablename = 'telemetry_processing_event'
                                          AND (
                                              (
                                                  indexname = 'ix_telemetry_processing_event_due_work'
                                                  AND indexdef LIKE '%(next_attempt_at, created_at, id)%'
                                                  AND indexdef LIKE '%WHERE%PENDING%'
                                              )
                                              OR (
                                                  indexname = 'ix_telemetry_processing_event_expired_lease'
                                                  AND indexdef LIKE '%(lease_expires_at, created_at, id)%'
                                                  AND indexdef LIKE '%WHERE%PROCESSING%'
                                              )
                                              OR (
                                                  indexname = 'ix_telemetry_processing_event_dead_organisation_time_id'
                                                  AND indexdef LIKE '%(organisation_id, dead_at DESC, id)%'
                                                  AND indexdef LIKE '%WHERE%DEAD%'
                                              )
                                          )
                                        ORDER BY indexname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ix_telemetry_processing_event_dead_organisation_time_id",
                        "ix_telemetry_processing_event_due_work",
                        "ix_telemetry_processing_event_expired_lease");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT conname
                                        FROM pg_constraint
                                        WHERE conrelid = 'telemetry_processing_event'::regclass
                                          AND conname LIKE 'ck_telemetry_processing_event_%'
                                        ORDER BY conname
                                        """)
                                .query(String.class)
                                .list())
                .contains(
                        "ck_telemetry_processing_event_attempt_count",
                        "ck_telemetry_processing_event_claim_owner",
                        "ck_telemetry_processing_event_error_fields",
                        "ck_telemetry_processing_event_state_consistency",
                        "ck_telemetry_processing_event_status");
    }

    private void assertAlertSchema(JdbcClient jdbcClient) {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS(
                                            '|',
                                            column_name,
                                            data_type,
                                            COALESCE(character_maximum_length::text, '-'),
                                            is_nullable,
                                            (column_default IS NOT NULL)::text
                                        )
                                        FROM information_schema.columns
                                        WHERE table_schema = 'public'
                                          AND table_name = 'alert'
                                        ORDER BY column_name
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "cooldown_until|timestamp with time zone|-|NO|false",
                        "created_at|timestamp with time zone|-|NO|false",
                        "fingerprint|character|64|NO|false",
                        "first_occurred_at|timestamp with time zone|-|NO|false",
                        "id|uuid|-|NO|false",
                        "last_occurred_at|timestamp with time zone|-|NO|false",
                        "occurrence_count|bigint|-|NO|true",
                        "organisation_id|uuid|-|NO|false",
                        "status|character varying|16|NO|true",
                        "threshold_rule_id|uuid|-|NO|false",
                        "updated_at|timestamp with time zone|-|NO|false");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT indexname
                                        FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND tablename = 'alert'
                                          AND indexname IN (
                                              'ix_alert_organisation_status_last_occurred_id',
                                              'uq_alert_organisation_fingerprint',
                                              'uq_alert_organisation_rule'
                                          )
                                        ORDER BY indexname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ix_alert_organisation_status_last_occurred_id",
                        "uq_alert_organisation_fingerprint",
                        "uq_alert_organisation_rule");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT index_class.relname
                                        FROM pg_index index_metadata
                                        JOIN pg_class index_class
                                          ON index_class.oid = index_metadata.indexrelid
                                        WHERE index_metadata.indrelid = 'alert'::regclass
                                          AND index_metadata.indisunique
                                          AND index_metadata.indpred IS NOT NULL
                                        ORDER BY index_class.relname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly("uq_alert_organisation_fingerprint", "uq_alert_organisation_rule");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT conname
                                        FROM pg_constraint
                                        WHERE conrelid = 'alert'::regclass
                                        ORDER BY conname
                                        """)
                                .query(String.class)
                                .list())
                .contains(
                        "ck_alert_fingerprint",
                        "ck_alert_occurrence_count",
                        "ck_alert_occurrence_timestamps",
                        "ck_alert_record_timestamps",
                        "ck_alert_status",
                        "fk_alert_organisation",
                        "fk_alert_threshold_rule",
                        "pk_alert",
                        "uq_alert_organisation_id")
                .doesNotContain("uq_alert_organisation_fingerprint", "uq_alert_organisation_rule");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM pg_constraint
                                        WHERE conrelid = 'app_user'::regclass
                                          AND conname = 'uq_app_user_organisation_id'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS(
                                            '|',
                                            column_name,
                                            data_type,
                                            COALESCE(character_maximum_length::text, '-'),
                                            is_nullable
                                        )
                                        FROM information_schema.columns
                                        WHERE table_schema = 'public'
                                          AND table_name = 'alert_status_history'
                                        ORDER BY column_name
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "actor_user_id|uuid|-|NO",
                        "alert_id|uuid|-|NO",
                        "from_status|character varying|16|NO",
                        "organisation_id|uuid|-|NO",
                        "sequence_number|smallint|-|NO",
                        "to_status|character varying|16|NO",
                        "transitioned_at|timestamp with time zone|-|NO");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT conname
                                        FROM pg_constraint
                                        WHERE conrelid = 'alert_status_history'::regclass
                                        ORDER BY conname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ck_alert_status_history_transition",
                        "fk_alert_status_history_actor",
                        "fk_alert_status_history_alert",
                        "pk_alert_status_history");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT tgname
                                        FROM pg_trigger
                                        WHERE tgrelid = 'alert_status_history'::regclass
                                          AND NOT tgisinternal
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly("tr_alert_status_history_immutable");
    }

    private void assertWorkOrderSchema(JdbcClient jdbcClient) {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS(
                                            '|',
                                            column_name,
                                            data_type,
                                            COALESCE(character_maximum_length::text, '-'),
                                            is_nullable,
                                            (column_default IS NOT NULL)::text
                                        )
                                        FROM information_schema.columns
                                        WHERE table_schema = 'public'
                                          AND table_name = 'work_order'
                                        ORDER BY column_name
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "alert_id|uuid|-|NO|false",
                        "assigned_at|timestamp with time zone|-|YES|false",
                        "assigned_technician_role_code|character varying|32|YES|false",
                        "assigned_technician_user_id|uuid|-|YES|false",
                        "created_at|timestamp with time zone|-|NO|true",
                        "id|uuid|-|NO|false",
                        "organisation_id|uuid|-|NO|false",
                        "status|character varying|16|NO|true",
                        "updated_at|timestamp with time zone|-|NO|true",
                        "version|bigint|-|NO|true");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT conname
                                        FROM pg_constraint
                                        WHERE conrelid = 'work_order'::regclass
                                        ORDER BY conname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ck_work_order_assignment_consistency",
                        "ck_work_order_status",
                        "ck_work_order_timestamps",
                        "ck_work_order_version",
                        "fk_work_order_alert",
                        "fk_work_order_assigned_technician",
                        "pk_work_order",
                        "uq_work_order_organisation_alert",
                        "uq_work_order_organisation_id");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT indexname
                                        FROM pg_indexes
                                        WHERE schemaname = 'public'
                                          AND tablename = 'work_order'
                                          AND indexname IN (
                                              'ix_work_order_organisation_updated_id',
                                              'ix_work_order_organisation_assignee_updated_id'
                                          )
                                        ORDER BY indexname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ix_work_order_organisation_assignee_updated_id",
                        "ix_work_order_organisation_updated_id");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT tgname
                                        FROM pg_trigger
                                        WHERE tgrelid = 'work_order'::regclass
                                          AND NOT tgisinternal
                                        ORDER BY tgname
                                        """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "tr_work_order_assignment_immutable", "tr_work_order_scope_immutable");

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT COUNT(*)::integer
                                        FROM pg_constraint
                                        WHERE conrelid = 'app_user'::regclass
                                          AND conname = 'uq_app_user_organisation_id_role'
                                        """)
                                .query(Integer.class)
                                .single())
                .isOne();
    }

    private void assertWorkOrderHistorySchema(JdbcClient jdbcClient) {
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                SELECT CONCAT_WS('|', column_name, data_type, is_nullable)
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'work_order_status_history'
                ORDER BY column_name
                """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "actor_user_id|uuid|YES",
                        "from_status|character varying|NO",
                        "organisation_id|uuid|NO",
                        "sequence_number|smallint|NO",
                        "to_status|character varying|NO",
                        "transitioned_at|timestamp with time zone|NO",
                        "work_order_id|uuid|NO");
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                SELECT conname FROM pg_constraint
                WHERE conrelid = 'work_order_status_history'::regclass
                ORDER BY conname
                """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "ck_work_order_status_history_legacy_actor",
                        "ck_work_order_status_history_transition",
                        "fk_work_order_status_history_actor",
                        "fk_work_order_status_history_work_order",
                        "pk_work_order_status_history");
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = 'work_order_status_history'::regclass AND NOT tgisinternal
                ORDER BY tgname
                """)
                                .query(String.class)
                                .list())
                .containsExactly(
                        "tr_work_order_status_history_immutable",
                        "tr_work_order_status_history_insert");
    }

    private void assertWorkOrderConstraints(JdbcClient jdbcClient) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        )
                        VALUES
                            (
                                '80000000-0000-0000-0000-000000000011',
                                '00000000-0000-0000-0000-000000000001',
                                '40000000-0000-0000-0000-000000000001',
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:05:00+00',
                                '2026-08-22 09:00:00+00',
                                '2026-08-22 09:00:00+00'
                            ),
                            (
                                '80000000-0000-0000-0000-000000000012',
                                '00000000-0000-0000-0000-000000000001',
                                '40000000-0000-0000-0000-000000000002',
                                'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:05:00+00',
                                '2026-08-22 09:00:00+00',
                                '2026-08-22 09:00:00+00'
                            ),
                            (
                                '80000000-0000-0000-0000-000000000013',
                                '00000000-0000-0000-0000-000000000002',
                                '40000000-0000-0000-0000-000000000003',
                                'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:00:00+00',
                                '2026-08-22 08:05:00+00',
                                '2026-08-22 09:00:00+00',
                                '2026-08-22 09:00:00+00'
                            )
                        """)
                .update();

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
                            '90000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000001',
                            '80000000-0000-0000-0000-000000000011',
                            '2026-08-22 10:00:00+00',
                            '2026-08-22 10:00:00+00'
                        )
                        """)
                .update();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO work_order (id, organisation_id, alert_id)
                                                VALUES (
                                                    '90000000-0000-0000-0000-000000000002',
                                                    '00000000-0000-0000-0000-000000000002',
                                                    '80000000-0000-0000-0000-000000000011'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE work_order
                                                SET organisation_id = '00000000-0000-0000-0000-000000000002'
                                                WHERE id = '90000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        SELECT CONCAT_WS('|', organisation_id, alert_id)
                                        FROM work_order
                                        WHERE id = '90000000-0000-0000-0000-000000000001'
                                        """)
                                .query(String.class)
                                .single())
                .isEqualTo(
                        "00000000-0000-0000-0000-000000000001|"
                                + "80000000-0000-0000-0000-000000000011");
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO work_order (id, organisation_id, alert_id)
                                                VALUES (
                                                    '90000000-0000-0000-0000-000000000003',
                                                    '00000000-0000-0000-0000-000000000001',
                                                    '80000000-0000-0000-0000-000000000011'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
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
                                                    '90000000-0000-0000-0000-000000000004',
                                                    '00000000-0000-0000-0000-000000000001',
                                                    '80000000-0000-0000-0000-000000000012',
                                                    'ASSIGNED',
                                                    1,
                                                    '10000000-0000-0000-0000-000000000001',
                                                    'TECHNICIAN',
                                                    '2026-08-22 10:05:00+00',
                                                    '2026-08-22 10:00:00+00',
                                                    '2026-08-22 10:05:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE work_order
                                                SET alert_id = '80000000-0000-0000-0000-000000000012'
                                                WHERE id = '90000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE work_order
                                                SET status = 'ASSIGNED', version = 1
                                                WHERE id = '90000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(
                        jdbcClient
                                .sql(
                                        """
                                        UPDATE work_order
                                        SET status = 'ASSIGNED',
                                            version = 1,
                                            assigned_technician_user_id = '10000000-0000-0000-0000-000000000002',
                                            assigned_technician_role_code = 'TECHNICIAN',
                                            assigned_at = '2026-08-22 10:05:00+00',
                                            updated_at = '2026-08-22 10:05:00+00'
                                        WHERE id = '90000000-0000-0000-0000-000000000001'
                                        """)
                                .update())
                .isOne();

        assertWorkOrderHistoryConstraints(jdbcClient);
        jdbcClient.sql("TRUNCATE TABLE work_order_status_history").update();
        jdbcClient.sql("DELETE FROM work_order").update();
        jdbcClient
                .sql(
                        """
                        DELETE FROM alert
                        WHERE id IN (
                            '80000000-0000-0000-0000-000000000011',
                            '80000000-0000-0000-0000-000000000012',
                            '80000000-0000-0000-0000-000000000013'
                        )
                        """)
                .update();
    }

    private void assertWorkOrderHistoryConstraints(JdbcClient jdbcClient) {
        UUID workOrderId = UUID.fromString("90000000-0000-0000-0000-000000000001");
        UUID adminId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID technicianId = UUID.fromString("10000000-0000-0000-0000-000000000002");
        UUID foreignAdminId = UUID.fromString("10000000-0000-0000-0000-000000000004");
        UUID foreignOrganisationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        for (String invalid :
                List.of(
                        "status = 'IN_PROGRESS', version = 1",
                        "status = 'IN_PROGRESS', version = 3",
                        "status = 'DONE', version = 2",
                        "status = 'BLOCKED', version = 2",
                        "version = -1",
                        "assigned_technician_user_id = '10000000-0000-0000-0000-000000000001'",
                        "assigned_technician_role_code = NULL",
                        "assigned_at = '2026-08-22 10:04:00+00'",
                        "status = 'OPEN', version = 0, assigned_technician_user_id = NULL, assigned_technician_role_code = NULL, assigned_at = NULL")) {
            assertThatThrownBy(
                            () ->
                                    jdbcClient
                                            .sql(
                                                    "UPDATE work_order SET "
                                                            + invalid
                                                            + " WHERE id = :workOrderId")
                                            .param("workOrderId", workOrderId)
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        foreignAdminId,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        foreignOrganisationId,
                                        workOrderId,
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        foreignAdminId,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        UUID.randomUUID(),
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        adminId,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        null,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        1,
                                        "DONE",
                                        "ASSIGNED",
                                        adminId,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        adminId,
                                        "2026-08-22T10:06:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient
                .sql(
                        """
                UPDATE work_order SET status = 'IN_PROGRESS', version = 2, updated_at = '2026-08-22 10:10:00+00'
                WHERE id = :workOrderId
                """)
                .param("workOrderId", workOrderId)
                .update();
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        2,
                                        "ASSIGNED",
                                        "IN_PROGRESS",
                                        technicianId,
                                        "2026-08-22T10:10:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient
                .sql(
                        """
                UPDATE work_order SET status = 'ASSIGNED', version = 1, updated_at = '2026-08-22 10:05:00+00'
                WHERE id = :workOrderId
                """)
                .param("workOrderId", workOrderId)
                .update();
        assertThat(
                        insertWorkOrderHistory(
                                jdbcClient,
                                NORTHSTAR_ID,
                                workOrderId,
                                1,
                                "OPEN",
                                "ASSIGNED",
                                adminId,
                                "2026-08-22T10:05:00Z"))
                .isOne();
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        1,
                                        "OPEN",
                                        "ASSIGNED",
                                        adminId,
                                        "2026-08-22T10:05:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient
                .sql(
                        """
                UPDATE work_order SET status = 'IN_PROGRESS', version = 2, updated_at = '2026-08-22 10:10:00+00'
                WHERE id = :workOrderId
                """)
                .param("workOrderId", workOrderId)
                .update();
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        2,
                                        "ASSIGNED",
                                        "IN_PROGRESS",
                                        null,
                                        "2026-08-22T10:10:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        insertWorkOrderHistory(
                                jdbcClient,
                                NORTHSTAR_ID,
                                workOrderId,
                                2,
                                "ASSIGNED",
                                "IN_PROGRESS",
                                technicianId,
                                "2026-08-22T10:10:00Z"))
                .isOne();

        jdbcClient
                .sql(
                        """
                UPDATE work_order SET status = 'DONE', version = 3, updated_at = '2026-08-22 10:06:00+00'
                WHERE id = :workOrderId
                """)
                .param("workOrderId", workOrderId)
                .update();
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        3,
                                        "IN_PROGRESS",
                                        "DONE",
                                        technicianId,
                                        "2026-08-22T10:06:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient
                .sql(
                        """
                UPDATE work_order SET updated_at = '2026-08-22 10:15:00+00'
                WHERE id = :workOrderId
                """)
                .param("workOrderId", workOrderId)
                .update();
        assertThat(
                        insertWorkOrderHistory(
                                jdbcClient,
                                NORTHSTAR_ID,
                                workOrderId,
                                3,
                                "IN_PROGRESS",
                                "DONE",
                                technicianId,
                                "2026-08-22T10:15:00Z"))
                .isOne();
        assertThatThrownBy(
                        () ->
                                insertWorkOrderHistory(
                                        jdbcClient,
                                        NORTHSTAR_ID,
                                        workOrderId,
                                        4,
                                        "DONE",
                                        "OPEN",
                                        technicianId,
                                        "2026-08-22T10:15:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
        List<String> history =
                jdbcClient
                        .sql(
                                "SELECT row_to_json(work_order_status_history)::text FROM work_order_status_history ORDER BY sequence_number")
                        .query(String.class)
                        .list();
        assertThat(history).hasSize(3);
        for (String mutation :
                List.of(
                        "UPDATE work_order_status_history SET actor_user_id = '10000000-0000-0000-0000-000000000002'",
                        "UPDATE work_order_status_history SET transitioned_at = transitioned_at + INTERVAL '1 minute'",
                        "UPDATE work_order_status_history SET organisation_id = '00000000-0000-0000-0000-000000000002'",
                        "DELETE FROM work_order_status_history")) {
            assertThatThrownBy(() -> jdbcClient.sql(mutation).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(
                        jdbcClient
                                .sql(
                                        "SELECT row_to_json(work_order_status_history)::text FROM work_order_status_history ORDER BY sequence_number")
                                .query(String.class)
                                .list())
                .isEqualTo(history);
    }

    private int insertWorkOrderHistory(
            JdbcClient jdbcClient,
            UUID organisationId,
            UUID workOrderId,
            int sequenceNumber,
            String fromStatus,
            String toStatus,
            UUID actorId,
            String transitionedAt) {
        return jdbcClient
                .sql(
                        """
                INSERT INTO work_order_status_history (
                    organisation_id, work_order_id, sequence_number, from_status, to_status, actor_user_id, transitioned_at
                ) VALUES (:organisationId, :workOrderId, :sequenceNumber, :fromStatus, :toStatus, :actorId, :transitionedAt)
                """)
                .param("organisationId", organisationId)
                .param("workOrderId", workOrderId)
                .param("sequenceNumber", sequenceNumber)
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("actorId", actorId)
                .param("transitionedAt", OffsetDateTime.parse(transitionedAt))
                .update();
    }

    private void assertAlertHistoryConstraints(JdbcClient jdbcClient) {
        jdbcClient
                .sql(
                        """
                        INSERT INTO alert (
                            id,
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            '80000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000001',
                            '40000000-0000-0000-0000-000000000001',
                            '1111111111111111111111111111111111111111111111111111111111111111',
                            '2026-08-21 08:00:00+00',
                            '2026-08-21 08:00:00+00',
                            '2026-08-21 08:05:00+00',
                            '2026-08-21 09:00:00+00',
                            '2026-08-21 09:00:00+00'
                        )
                        """)
                .update();
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
                            '00000000-0000-0000-0000-000000000001',
                            '80000000-0000-0000-0000-000000000001',
                            1,
                            'OPEN',
                            'ACKNOWLEDGED',
                            '10000000-0000-0000-0000-000000000001',
                            '2026-08-21 09:05:00+00'
                        )
                        """)
                .update();

        assertThatThrownBy(
                        () ->
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
                                                    '00000000-0000-0000-0000-000000000002',
                                                    '80000000-0000-0000-0000-000000000001',
                                                    1,
                                                    'OPEN',
                                                    'ACKNOWLEDGED',
                                                    '10000000-0000-0000-0000-000000000004',
                                                    '2026-08-21 09:05:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
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
                                                    '00000000-0000-0000-0000-000000000001',
                                                    '80000000-0000-0000-0000-000000000001',
                                                    2,
                                                    'ACKNOWLEDGED',
                                                    'RESOLVED',
                                                    '10000000-0000-0000-0000-000000000004',
                                                    '2026-08-21 09:10:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
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
                                                    '00000000-0000-0000-0000-000000000001',
                                                    '80000000-0000-0000-0000-000000000001',
                                                    2,
                                                    'OPEN',
                                                    'RESOLVED',
                                                    '10000000-0000-0000-0000-000000000001',
                                                    '2026-08-21 09:10:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE alert_status_history
                                                SET transitioned_at = '2026-08-21 09:06:00+00'
                                                WHERE organisation_id = '00000000-0000-0000-0000-000000000001'
                                                  AND alert_id = '80000000-0000-0000-0000-000000000001'
                                                  AND sequence_number = 1
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                DELETE FROM alert_status_history
                                                WHERE organisation_id = '00000000-0000-0000-0000-000000000001'
                                                  AND alert_id = '80000000-0000-0000-0000-000000000001'
                                                  AND sequence_number = 1
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("TRUNCATE TABLE alert_status_history").update();
        jdbcClient
                .sql(
                        """
                        DELETE FROM alert
                        WHERE id = '80000000-0000-0000-0000-000000000001'
                        """)
                .update();
    }

    private void assertDatabaseConstraints(JdbcClient jdbcClient) {
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO asset (
                                                    id,
                                                    organisation_id,
                                                    asset_code,
                                                    name
                                                )
                                                VALUES (
                                                    '29999999-0000-0000-0000-000000000001',
                                                    '99999999-0000-0000-0000-000000000001',
                                                    'PUMP-999',
                                                    'Unowned Pump'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

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
                            '50000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000001',
                            'constraint-evidence',
                            '0000000000000000000000000000000000000000000000000000000000000000',
                            1,
                            '2026-08-13 12:00:00+00'
                        )
                        """)
                .update();

        jdbcClient
                .sql(
                        """
                        INSERT INTO telemetry_processing_event (
                            id,
                            organisation_id,
                            telemetry_batch_id,
                            event_type,
                            created_at
                        )
                        VALUES (
                            '70000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000001',
                            '50000000-0000-0000-0000-000000000001',
                            'TELEMETRY_BATCH_ACCEPTED',
                            '2026-08-13 12:00:00+00'
                        )
                        """)
                .update();

        assertThat(
                        count(
                                jdbcClient,
                                """
                                SELECT COUNT(*)::integer
                                FROM telemetry_processing_event
                                WHERE id = '70000000-0000-0000-0000-000000000001'
                                  AND status = 'PENDING'
                                  AND attempt_count = 0
                                  AND next_attempt_at IS NOT NULL
                                  AND updated_at IS NOT NULL
                                  AND next_attempt_at = updated_at
                                  AND claim_token IS NULL
                                  AND claim_owner IS NULL
                                  AND lease_expires_at IS NULL
                                  AND completed_at IS NULL
                                  AND dead_at IS NULL
                                  AND last_error_code IS NULL
                                  AND last_error_message IS NULL
                                """))
                .isOne();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE telemetry_processing_event
                                                SET status = 'UNKNOWN'
                                                WHERE id = '70000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE telemetry_processing_event
                                                SET attempt_count = 6
                                                WHERE id = '70000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE telemetry_processing_event
                                                SET status = 'PROCESSING',
                                                    attempt_count = 1,
                                                    next_attempt_at = NULL
                                                WHERE id = '70000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                UPDATE telemetry_processing_event
                                                SET last_error_code = REPEAT('X', 65),
                                                    last_error_message = 'Fixed safe message'
                                                WHERE id = '70000000-0000-0000-0000-000000000001'
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient
                .sql(
                        """
                        DELETE FROM telemetry_processing_event
                        WHERE id = '70000000-0000-0000-0000-000000000001'
                        """)
                .update();

        assertThatThrownBy(
                        () ->
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
                                                    '60000000-0000-0000-0000-000000000001',
                                                    '00000000-0000-0000-0000-000000000002',
                                                    '50000000-0000-0000-0000-000000000001',
                                                    0,
                                                    '30000000-0000-0000-0000-000000000003',
                                                    70.000000,
                                                    '2026-08-13 12:00:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
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
                                                    '60000000-0000-0000-0000-000000000002',
                                                    '00000000-0000-0000-0000-000000000001',
                                                    '50000000-0000-0000-0000-000000000001',
                                                    0,
                                                    '30000000-0000-0000-0000-000000000003',
                                                    70.000000,
                                                    '2026-08-13 12:00:00+00'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient
                .sql(
                        """
                        DELETE FROM telemetry_batch
                        WHERE id = '50000000-0000-0000-0000-000000000001'
                        """)
                .update();

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO sensor (
                                                    id,
                                                    organisation_id,
                                                    asset_id,
                                                    sensor_key,
                                                    name,
                                                    measurement_type,
                                                    unit
                                                )
                                                VALUES (
                                                    '39999999-0000-0000-0000-000000000001',
                                                    '00000000-0000-0000-0000-000000000002',
                                                    '20000000-0000-0000-0000-000000000001',
                                                    'FOREIGN-TEMP',
                                                    'Foreign Temperature',
                                                    'TEMPERATURE',
                                                    'CELSIUS'
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO threshold_rule (
                                                    id,
                                                    organisation_id,
                                                    sensor_id,
                                                    rule_code,
                                                    name,
                                                    comparison,
                                                    threshold_value,
                                                    cooldown_seconds,
                                                    enabled
                                                )
                                                VALUES (
                                                    '49999999-0000-0000-0000-000000000001',
                                                    '00000000-0000-0000-0000-000000000002',
                                                    '30000000-0000-0000-0000-000000000001',
                                                    'FOREIGN-HIGH-TEMP',
                                                    'Foreign High Temperature',
                                                    'GREATER_THAN_OR_EQUAL_TO',
                                                    90.000000,
                                                    300,
                                                    TRUE
                                                )
                                                """)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                """
                                                INSERT INTO asset (
                                                    id,
                                                    organisation_id,
                                                    asset_code,
                                                    name
                                                )
                                                VALUES (
                                                    '29999999-0000-0000-0000-000000000002',
                                                    :organisationId,
                                                    'PUMP-101',
                                                    'Duplicate Pump'
                                                )
                                                """)
                                        .param("organisationId", NORTHSTAR_ID)
                                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int count(JdbcClient jdbcClient, String sql) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }

    private record DatabaseState(
            String currentMigration,
            int migrations,
            int organisations,
            int roles,
            int users,
            int assets,
            int sensors,
            int thresholdRules,
            int telemetryBatches,
            int telemetryReadings,
            int telemetryProcessingEvents,
            int alerts,
            int alertHistoryEntries,
            int workOrders,
            int workOrderHistoryEntries,
            int auditEvents) {}
}
