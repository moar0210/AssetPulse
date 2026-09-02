function matchingAlert(alert, sensorId, ruleCode) {
  const lastOccurredAt = Date.parse(alert?.lastOccurredAt);
  return (
    typeof alert?.id === "string" &&
    alert.id.length > 0 &&
    alert?.context?.sensor?.id === sensorId &&
    alert?.context?.thresholdRule?.ruleCode === ruleCode &&
    Number.isSafeInteger(alert?.occurrenceCount) &&
    alert.occurrenceCount >= 1 &&
    Number.isFinite(lastOccurredAt)
  );
}

export function captureMatchingAlertBaseline(payload, sensorId, ruleCode) {
  if (!Array.isArray(payload?.alerts)) {
    return null;
  }

  const baseline = [];
  for (const alert of payload.alerts) {
    if (matchingAlert(alert, sensorId, ruleCode)) {
      baseline.push({
        id: alert.id,
        occurrenceCount: alert.occurrenceCount,
        lastOccurredAtMillis: Date.parse(alert.lastOccurredAt),
      });
    }
  }
  return baseline;
}

export function evidenceWindowFollowsBaseline(
  baseline,
  expectedLastOccurredAtMillis,
) {
  return (
    Array.isArray(baseline) &&
    Number.isSafeInteger(expectedLastOccurredAtMillis) &&
    baseline.every(
      (alert) => alert.lastOccurredAtMillis < expectedLastOccurredAtMillis,
    )
  );
}

export function hasCausalAlertAdvance(
  payload,
  baseline,
  sensorId,
  ruleCode,
  expectedLastOccurredAtMillis,
  expectedOccurrenceDelta,
) {
  if (
    !Array.isArray(baseline) ||
    !Number.isSafeInteger(expectedLastOccurredAtMillis) ||
    !Number.isSafeInteger(expectedOccurrenceDelta) ||
    expectedOccurrenceDelta < 1
  ) {
    return false;
  }

  const current = captureMatchingAlertBaseline(payload, sensorId, ruleCode);
  if (current === null) {
    return false;
  }

  return current.some((alert) => {
    if (alert.lastOccurredAtMillis !== expectedLastOccurredAtMillis) {
      return false;
    }
    const previous = baseline.find((candidate) => candidate.id === alert.id);
    const previousOccurrenceCount = previous?.occurrenceCount ?? 0;
    return (
      alert.occurrenceCount ===
      previousOccurrenceCount + expectedOccurrenceDelta
    );
  });
}
