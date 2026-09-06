package io.github.moar0210.assetpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class TelemetryBatchTransactionIntegrationTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NORTHSTAR_SENSOR_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private TelemetryBatchService telemetryBatchService;
    @Autowired private JdbcClient jdbcClient;
    @MockitoSpyBean private TelemetryProcessingEventRepository processingEventRepository;

    @BeforeEach
    void clearTelemetry() {
        jdbcClient.sql("DELETE FROM telemetry_processing_event").update();
        jdbcClient.sql("DELETE FROM telemetry_reading").update();
        jdbcClient.sql("DELETE FROM telemetry_batch").update();
    }

    @Test
    void eventInsertFailureRollsBackBatchReadingsAndEvent() {
        AtomicBoolean readingsWereVisibleBeforeFailure = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            assertThat(count("telemetry_batch")).isOne();
                            assertThat(count("telemetry_reading")).isOne();
                            assertThat(count("telemetry_processing_event")).isZero();
                            readingsWereVisibleBeforeFailure.set(true);
                            throw new IllegalStateException("simulated event insert failure");
                        })
                .when(processingEventRepository)
                .insertBatchAccepted(
                        eq(NORTHSTAR_ID), any(UUID.class), any(Instant.class), any(), any());

        TelemetryBatchRequest request =
                new TelemetryBatchRequest(
                        "event-failure",
                        List.of(
                                new TelemetryBatchRequest.Reading(
                                        NORTHSTAR_SENSOR_ID,
                                        new BigDecimal("72.500000"),
                                        Instant.parse("2026-08-13T12:00:00Z"))));

        assertThatThrownBy(() -> telemetryBatchService.accept(NORTHSTAR_ID, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated event insert failure");

        assertThat(readingsWereVisibleBeforeFailure).isTrue();
        assertThat(count("telemetry_batch")).isZero();
        assertThat(count("telemetry_reading")).isZero();
        assertThat(count("telemetry_processing_event")).isZero();
    }

    private int count(String table) {
        return jdbcClient
                .sql("SELECT COUNT(*)::integer FROM " + table)
                .query(Integer.class)
                .single();
    }
}
