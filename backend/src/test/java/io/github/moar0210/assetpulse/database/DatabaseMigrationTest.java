package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moar0210.assetpulse.AssetPulseApplication;
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
            assertDatabaseConstraints(jdbcClient);
            firstState = readState(jdbcClient);
            firstSeedRows = readSeedRows(jdbcClient);
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication()) {
            JdbcClient jdbcClient = restartedApplication.getBean(JdbcClient.class);

            assertThat(readState(jdbcClient)).isEqualTo(firstState);
            assertThat(readSeedRows(jdbcClient)).containsExactlyElementsOf(firstSeedRows);
        }

        assertThat(firstState).isEqualTo(new DatabaseState("7", 7, 2, 3, 4, 3, 3, 3, 0, 0, 0));
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
                count(jdbcClient, "SELECT COUNT(*)::integer FROM telemetry_processing_event"));
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
            int telemetryProcessingEvents) {}
}
