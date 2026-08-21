package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.assets.ThresholdComparison;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ThresholdRuleEvaluator {

    public boolean isBreached(
            BigDecimal value, ThresholdComparison comparison, BigDecimal thresholdValue) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(comparison);
        Objects.requireNonNull(thresholdValue);

        return switch (comparison) {
            case GREATER_THAN_OR_EQUAL_TO -> value.compareTo(thresholdValue) >= 0;
        };
    }
}
