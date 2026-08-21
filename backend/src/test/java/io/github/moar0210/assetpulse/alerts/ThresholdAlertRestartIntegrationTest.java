package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moar0210.assetpulse.AssetPulseApplication;
import io.github.moar0210.assetpulse.telemetry.TelemetryBatchRequest;
import io.github.moar0210.assetpulse.telemetry.TelemetryBatchResponse;
import io.github.moar0210.assetpulse.telemetry.TelemetryBatchService;
import io.github.moar0210.assetpulse.telemetry.TelemetryProcessingWorker;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class ThresholdAlertRestartIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final String EXPECTED_FINGERPRINT =
            "1f4c1d7982a9b538ce9ee20182718662f1c82686e9e424e0679ff1bed54a4086";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-21T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @Test
    void restartedEnabledWorkerDrainsCommittedPendingWorkIntoOneCorrectAlert() {
        UUID batchId;

        try (ConfigurableApplicationContext firstApplication = startApplication(false)) {
            TelemetryBatchResponse accepted =
                    firstApplication
                            .getBean(TelemetryBatchService.class)
                            .accept(
                                    NORTHSTAR_ID,
                                    new TelemetryBatchRequest(
                                            "restart-safe-threshold-alert",
                                            List.of(
                                                    new TelemetryBatchRequest.Reading(
                                                            NORTHSTAR_SENSOR_ID,
                                                            new BigDecimal("80.000000"),
                                                            OBSERVED_AT))));
            batchId = accepted.batchId();

            JdbcClient jdbcClient = firstApplication.getBean(JdbcClient.class);
            assertThat(readEventStatus(jdbcClient, batchId)).isEqualTo("PENDING");
            assertThat(countAlerts(jdbcClient)).isZero();
            assertThat(firstApplication.getBeansOfType(TelemetryProcessingWorker.class)).isEmpty();
        }

        try (ConfigurableApplicationContext restartedApplication = startApplication(true)) {
            JdbcClient jdbcClient = restartedApplication.getBean(JdbcClient.class);
            assertThat(readEventStatus(jdbcClient, batchId)).isEqualTo("PENDING");
            assertThat(countAlerts(jdbcClient)).isZero();

            TelemetryProcessingWorker worker =
                    restartedApplication.getBean(TelemetryProcessingWorker.class);
            worker.poll();

            assertThat(readEventStatus(jdbcClient, batchId)).isEqualTo("COMPLETED");
            assertThat(countAlerts(jdbcClient)).isOne();
            assertThat(readOnlyAlert(jdbcClient))
                    .isEqualTo(
                            new AlertRow(
                                    NORTHSTAR_ID,
                                    NORTHSTAR_RULE_ID,
                                    EXPECTED_FINGERPRINT,
                                    "OPEN",
                                    1,
                                    OBSERVED_AT,
                                    OBSERVED_AT,
                                    OBSERVED_AT.plusSeconds(300)));
        }
    }

    private ConfigurableApplicationContext startApplication(boolean processingEnabled) {
        return new SpringApplicationBuilder(AssetPulseApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + POSTGRESQL.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRESQL.getUsername(),
                        "--spring.datasource.password=" + POSTGRESQL.getPassword(),
                        "--assetpulse.telemetry.processing.enabled=" + processingEnabled,
                        "--assetpulse.telemetry.processing.initial-delay-millis=3600000",
                        "--assetpulse.telemetry.processing.poll-delay-millis=3600000");
    }

    private String readEventStatus(JdbcClient jdbcClient, UUID batchId) {
        return jdbcClient
                .sql(
                        """
                        SELECT status
                        FROM telemetry_processing_event
                        WHERE organisation_id = :organisationId
                          AND telemetry_batch_id = :batchId
                        """)
                .param("organisationId", NORTHSTAR_ID)
                .param("batchId", batchId)
                .query(String.class)
                .single();
    }

    private int countAlerts(JdbcClient jdbcClient) {
        return jdbcClient.sql("SELECT COUNT(*)::integer FROM alert").query(Integer.class).single();
    }

    private AlertRow readOnlyAlert(JdbcClient jdbcClient) {
        return jdbcClient
                .sql(
                        """
                        SELECT
                            organisation_id,
                            threshold_rule_id,
                            fingerprint,
                            status,
                            occurrence_count,
                            first_occurred_at,
                            last_occurred_at,
                            cooldown_until
                        FROM alert
                        """)
                .query(ThresholdAlertRestartIntegrationTest::mapAlert)
                .single();
    }

    private static AlertRow mapAlert(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AlertRow(
                resultSet.getObject("organisation_id", UUID.class),
                resultSet.getObject("threshold_rule_id", UUID.class),
                resultSet.getString("fingerprint"),
                resultSet.getString("status"),
                resultSet.getLong("occurrence_count"),
                readInstant(resultSet, "first_occurred_at"),
                readInstant(resultSet, "last_occurred_at"),
                readInstant(resultSet, "cooldown_until"));
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record AlertRow(
            UUID organisationId,
            UUID thresholdRuleId,
            String fingerprint,
            String status,
            long occurrenceCount,
            Instant firstOccurredAt,
            Instant lastOccurredAt,
            Instant cooldownUntil) {}
}
