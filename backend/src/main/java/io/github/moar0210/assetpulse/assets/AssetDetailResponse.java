package io.github.moar0210.assetpulse.assets;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AssetDetailResponse(
        UUID id, String assetCode, String name, List<SensorResponse> sensors) {

    public AssetDetailResponse {
        sensors = List.copyOf(sensors);
    }

    public record SensorResponse(
            UUID id,
            String sensorKey,
            String name,
            MeasurementType measurementType,
            MeasurementUnit unit,
            List<ThresholdRuleResponse> thresholdRules) {

        public SensorResponse {
            thresholdRules = List.copyOf(thresholdRules);
        }
    }

    public record ThresholdRuleResponse(
            UUID id,
            String ruleCode,
            String name,
            ThresholdComparison comparison,
            BigDecimal thresholdValue,
            int cooldownSeconds,
            boolean enabled) {}
}
