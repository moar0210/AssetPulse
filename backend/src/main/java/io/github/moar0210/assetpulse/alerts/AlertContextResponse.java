package io.github.moar0210.assetpulse.alerts;

import io.github.moar0210.assetpulse.assets.MeasurementType;
import io.github.moar0210.assetpulse.assets.MeasurementUnit;
import io.github.moar0210.assetpulse.assets.ThresholdComparison;
import java.math.BigDecimal;
import java.util.UUID;

public record AlertContextResponse(
        AssetResponse asset, SensorResponse sensor, ThresholdRuleResponse thresholdRule) {

    public record AssetResponse(UUID id, String assetCode, String name) {}

    public record SensorResponse(
            UUID id,
            String sensorKey,
            String name,
            MeasurementType measurementType,
            MeasurementUnit unit) {}

    public record ThresholdRuleResponse(
            UUID id,
            String ruleCode,
            String name,
            ThresholdComparison comparison,
            BigDecimal thresholdValue,
            int cooldownSeconds) {}
}
