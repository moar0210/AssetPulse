package io.github.moar0210.assetpulse.demo;

import java.time.Instant;

public record DemoResetResponse(Instant resetAt, int alertsResolved, int workOrdersCompleted) {}
