import assert from "node:assert/strict";
import test from "node:test";

import {
  captureMatchingAlertBaseline,
  evidenceWindowFollowsBaseline,
  hasCausalAlertAdvance,
} from "./alert-evidence.mjs";

const SENSOR_ID = "30000000-0000-0000-0000-000000000001";
const RULE_CODE = "PUMP-101-HIGH-TEMP";
const EXPECTED_LAST_OCCURRED_AT = Date.parse("2026-09-02T12:00:00.000Z");

function alert({
  id = "50000000-0000-0000-0000-000000000001",
  occurrenceCount = 4,
  lastOccurredAt = "2026-09-02T11:00:00.000Z",
  sensorId = SENSOR_ID,
  ruleCode = RULE_CODE,
} = {}) {
  return {
    id,
    status: "OPEN",
    occurrenceCount,
    lastOccurredAt,
    context: {
      sensor: { id: sensorId },
      thresholdRule: { ruleCode },
    },
  };
}

test("requires an exact occurrence advance and submitted latest timestamp", () => {
  const before = { alerts: [alert()] };
  const baseline = captureMatchingAlertBaseline(before, SENSOR_ID, RULE_CODE);
  const after = {
    alerts: [
      alert({
        occurrenceCount: 7,
        lastOccurredAt: "2026-09-02T12:00:00.000Z",
      }),
    ],
  };

  assert.equal(
    hasCausalAlertAdvance(
      after,
      baseline,
      SENSOR_ID,
      RULE_CODE,
      EXPECTED_LAST_OCCURRED_AT,
      3,
    ),
    true,
  );
});

test("does not accept an unchanged pre-existing alert", () => {
  const existing = alert({
    occurrenceCount: 7,
    lastOccurredAt: "2026-09-02T12:00:00.000Z",
  });
  const baseline = captureMatchingAlertBaseline(
    { alerts: [existing] },
    SENSOR_ID,
    RULE_CODE,
  );

  assert.equal(
    hasCausalAlertAdvance(
      { alerts: [existing] },
      baseline,
      SENSOR_ID,
      RULE_CODE,
      EXPECTED_LAST_OCCURRED_AT,
      3,
    ),
    false,
  );
});

test("fails closed when concurrent activity adds an extra occurrence", () => {
  const baseline = captureMatchingAlertBaseline(
    { alerts: [alert()] },
    SENSOR_ID,
    RULE_CODE,
  );
  const after = {
    alerts: [
      alert({
        occurrenceCount: 8,
        lastOccurredAt: "2026-09-02T12:00:00.000Z",
      }),
    ],
  };

  assert.equal(
    hasCausalAlertAdvance(
      after,
      baseline,
      SENSOR_ID,
      RULE_CODE,
      EXPECTED_LAST_OCCURRED_AT,
      3,
    ),
    false,
  );
});

test("accepts a new matching alert with exactly the expected occurrences", () => {
  const baseline = captureMatchingAlertBaseline(
    { alerts: [] },
    SENSOR_ID,
    RULE_CODE,
  );
  const after = {
    alerts: [
      alert({
        id: "50000000-0000-0000-0000-000000000002",
        occurrenceCount: 3,
        lastOccurredAt: "2026-09-02T12:00:00.000Z",
      }),
    ],
  };

  assert.equal(
    hasCausalAlertAdvance(
      after,
      baseline,
      SENSOR_ID,
      RULE_CODE,
      EXPECTED_LAST_OCCURRED_AT,
      3,
    ),
    true,
  );
});

test("rejects a measurement window that does not follow existing alert state", () => {
  const baseline = captureMatchingAlertBaseline(
    {
      alerts: [
        alert({ lastOccurredAt: "2026-09-02T12:00:00.000Z" }),
        alert({
          id: "50000000-0000-0000-0000-000000000003",
          lastOccurredAt: "2026-09-02T11:59:59.999Z",
        }),
      ],
    },
    SENSOR_ID,
    RULE_CODE,
  );

  assert.equal(
    evidenceWindowFollowsBaseline(baseline, EXPECTED_LAST_OCCURRED_AT),
    false,
  );
  assert.equal(
    evidenceWindowFollowsBaseline(baseline, EXPECTED_LAST_OCCURRED_AT + 1),
    true,
  );
});

test("rejects a malformed alert list instead of manufacturing evidence", () => {
  assert.equal(captureMatchingAlertBaseline({}, SENSOR_ID, RULE_CODE), null);
  assert.equal(
    hasCausalAlertAdvance(
      {},
      [],
      SENSOR_ID,
      RULE_CODE,
      EXPECTED_LAST_OCCURRED_AT,
      3,
    ),
    false,
  );
});
