package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlertStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is legal: {2}")
    @CsvSource({
        "OPEN, OPEN, false",
        "OPEN, ACKNOWLEDGED, true",
        "OPEN, RESOLVED, false",
        "ACKNOWLEDGED, OPEN, false",
        "ACKNOWLEDGED, ACKNOWLEDGED, false",
        "ACKNOWLEDGED, RESOLVED, true",
        "RESOLVED, OPEN, false",
        "RESOLVED, ACKNOWLEDGED, false",
        "RESOLVED, RESOLVED, false"
    })
    void permitsOnlyTheTwoForwardLifecycleTransitions(
            AlertStatus current, AlertStatus requested, boolean expected) {
        assertThat(current.canTransitionTo(requested)).isEqualTo(expected);
    }
}
