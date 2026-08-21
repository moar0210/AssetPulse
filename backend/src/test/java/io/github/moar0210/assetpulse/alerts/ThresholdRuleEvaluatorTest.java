package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moar0210.assetpulse.assets.ThresholdComparison;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ThresholdRuleEvaluatorTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("80.000000");

    private final ThresholdRuleEvaluator evaluator = new ThresholdRuleEvaluator();

    @Test
    void greaterThanOrEqualToBreachesAtTheExactThresholdAndAbove() {
        assertThat(
                        evaluator.isBreached(
                                new BigDecimal("79.999999"),
                                ThresholdComparison.GREATER_THAN_OR_EQUAL_TO,
                                THRESHOLD))
                .isFalse();
        assertThat(
                        evaluator.isBreached(
                                new BigDecimal("80"),
                                ThresholdComparison.GREATER_THAN_OR_EQUAL_TO,
                                THRESHOLD))
                .isTrue();
        assertThat(
                        evaluator.isBreached(
                                new BigDecimal("80.000001"),
                                ThresholdComparison.GREATER_THAN_OR_EQUAL_TO,
                                THRESHOLD))
                .isTrue();
    }

    @Test
    void rejectsMissingEvaluationInputs() {
        assertThatThrownBy(
                        () ->
                                evaluator.isBreached(
                                        null,
                                        ThresholdComparison.GREATER_THAN_OR_EQUAL_TO,
                                        THRESHOLD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> evaluator.isBreached(BigDecimal.ONE, null, THRESHOLD))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () ->
                                evaluator.isBreached(
                                        BigDecimal.ONE,
                                        ThresholdComparison.GREATER_THAN_OR_EQUAL_TO,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }
}
