package io.github.moar0210.assetpulse.database;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moar0210.assetpulse.AssetPulseApplication;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class DatabaseRuntimeBoundaryTest {

    @Test
    void h2DriverIsNotAvailable() {
        assertThatThrownBy(() -> Class.forName("org.h2.Driver"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void unavailablePostgresqlPreventsStartup() {
        assertThatThrownBy(
                        () ->
                                new SpringApplicationBuilder(AssetPulseApplication.class)
                                        .web(WebApplicationType.NONE)
                                        .run(
                                                "--spring.main.banner-mode=off",
                                                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/assetpulse?connectTimeout=1",
                                                "--spring.datasource.username=assetpulse",
                                                "--spring.datasource.password=unavailable",
                                                "--spring.datasource.hikari.connection-timeout=250",
                                                "--spring.datasource.hikari.validation-timeout=250"))
                .hasRootCauseInstanceOf(ConnectException.class)
                .hasStackTraceContaining("127.0.0.1:1");
    }
}
