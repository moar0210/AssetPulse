package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertFingerprintTest {

    private static final UUID NORTHSTAR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVERSIDE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PUMP_101_HIGH_TEMPERATURE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID PUMP_102_HIGH_TEMPERATURE_RULE_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000002");

    private final AlertFingerprint fingerprint = new AlertFingerprint();

    @Test
    void calculatesTheStableVersionedFingerprint() {
        assertThat(fingerprint.calculate(NORTHSTAR_ID, PUMP_101_HIGH_TEMPERATURE_RULE_ID))
                .isEqualTo("1f4c1d7982a9b538ce9ee20182718662f1c82686e9e424e0679ff1bed54a4086");
    }

    @Test
    void separatesOrganisationAndThresholdRuleIdentity() {
        String baseline = fingerprint.calculate(NORTHSTAR_ID, PUMP_101_HIGH_TEMPERATURE_RULE_ID);

        assertThat(fingerprint.calculate(RIVERSIDE_ID, PUMP_101_HIGH_TEMPERATURE_RULE_ID))
                .isNotEqualTo(baseline);
        assertThat(fingerprint.calculate(NORTHSTAR_ID, PUMP_102_HIGH_TEMPERATURE_RULE_ID))
                .isNotEqualTo(baseline);
    }

    @Test
    void rejectsMissingFingerprintComponents() {
        assertThatThrownBy(() -> fingerprint.calculate(null, PUMP_101_HIGH_TEMPERATURE_RULE_ID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fingerprint.calculate(NORTHSTAR_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
