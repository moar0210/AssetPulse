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

for (const scenarioName of ["normal", "overheating"]) {
  test(`${scenarioName} accepts the first safe anchor after UTC normalization`, () => {
    const anchor = resolveAnchor("0000-01-01T01:05:00+01:00", ANCHOR);
    const scenario = buildScenario(scenarioName, anchor);

    assert.equal(scenario.anchor, "0000-01-01T00:05:00.000Z");
    assert.equal(
      scenario.request.readings[0].observedAt,
      "0000-01-01T00:00:00.000Z",
    );
    assert.equal(scenario.request.readings.at(-1).observedAt, scenario.anchor);
    assert.equal(scenario.request.readings.length, 6);
    assert.match(scenario.request.idempotencyKey, /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/);
    assert.deepEqual(buildScenario(scenarioName, anchor), scenario);
    assert.deepEqual(
      buildScenario(scenarioName, new Date("0000-01-01T00:05:00Z")),
      scenario,
    );
  });

  test(`${scenarioName} accepts the last supported anchor without changing it`, () => {
    const anchor = new Date("9999-12-31T23:59:59.999Z");
    const scenario = buildScenario(scenarioName, anchor);

    assert.equal(scenario.anchor, "9999-12-31T23:59:59.999Z");
    assert.equal(
      scenario.request.readings[0].observedAt,
      "9999-12-31T23:54:59.999Z",
    );
    assert.equal(scenario.request.readings.at(-1).observedAt, scenario.anchor);
    assert.match(scenario.request.idempotencyKey, /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/);
  });

  test(`${scenarioName} rejects anchors whose readings cross a calendar boundary`, () => {
    for (const anchor of [
      new Date("0000-01-01T00:00:00Z"),
      new Date("0000-01-01T00:04:59.999Z"),
      new Date("0000-01-01T01:04:59.999+01:00"),
      new Date("0000-01-01T00:05:00+01:00"),
      new Date("9999-12-31T23:59:00-00:01"),
      new Date(-8_640_000_000_000_000),
      new Date(8_640_000_000_000_000),
    ]) {
      assert.throws(
        () => buildScenario(scenarioName, anchor),
        /supported telemetry range/,
        anchor.toISOString(),
      );
    }
  });
}
