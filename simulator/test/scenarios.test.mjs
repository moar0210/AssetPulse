import assert from "node:assert/strict";
import test from "node:test";

import {
  NORTHSTAR_TEMPERATURE_SENSOR_ID,
  buildScenario,
  resolveAnchor,
} from "../src/scenarios.mjs";

const ANCHOR = new Date("2026-08-15T11:55:00.000Z");

test("normal scenario is deterministic and remains below the seeded threshold", () => {
  const scenario = buildScenario("normal", ANCHOR);

  assert.equal(
    scenario.request.idempotencyKey,
    "simulator:normal:20260815T115500000Z",
  );
  assert.deepEqual(
    scenario.request.readings.map(({ sensorId, value, observedAt }) => ({
      sensorId,
      value,
      observedAt,
    })),
    [
      [68, "2026-08-15T11:50:00.000Z"],
      [69.5, "2026-08-15T11:51:00.000Z"],
      [71, "2026-08-15T11:52:00.000Z"],
      [72.25, "2026-08-15T11:53:00.000Z"],
      [71.5, "2026-08-15T11:54:00.000Z"],
      [70.75, "2026-08-15T11:55:00.000Z"],
    ].map(([value, observedAt]) => ({
      sensorId: NORTHSTAR_TEMPERATURE_SENSOR_ID,
      value,
      observedAt,
    })),
  );
  assert.ok(scenario.request.readings.every(({ value }) => value < 80));
  assert.deepEqual(buildScenario("normal", ANCHOR), scenario);
});

test("overheating scenario crosses the seeded threshold", () => {
  const scenario = buildScenario("overheating", ANCHOR);

  assert.equal(
    scenario.request.idempotencyKey,
    "simulator:overheating:20260815T115500000Z",
  );
  assert.deepEqual(
    scenario.request.readings.map(({ value }) => value),
    [72, 74, 77.5, 80, 83.5, 86],
  );
  assert.ok(scenario.request.readings.some(({ value }) => value >= 80));
});

test("anchor defaults to the current minute and rejects future or malformed input", () => {
  const now = new Date("2026-08-15T12:34:56.789Z");

  assert.equal(resolveAnchor(undefined, now).toISOString(), "2026-08-15T12:34:00.000Z");
  assert.equal(
    resolveAnchor("2026-08-15T14:34:56.789+02:00", now).toISOString(),
    now.toISOString(),
  );
  assert.throws(
    () => resolveAnchor("2026-08-15T12:34:56.790Z", now),
    /cannot be in the future/,
  );

  for (const malformed of [
    "not-an-instant",
    "2026-08-15",
    "2026-08-15T12:34:56",
    "2026-02-29T12:34:56Z",
    "2026-08-15T24:00:00Z",
    "2026-08-15T12:34:56.7890Z",
    "2026-08-15T12:34:56+24:00",
  ]) {
    assert.throws(() => resolveAnchor(malformed, now), /ISO-8601/);
  }
});
