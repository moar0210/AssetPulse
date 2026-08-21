import { parseIsoInstant } from "./validation.mjs";

export const NORTHSTAR_TEMPERATURE_SENSOR_ID =
  "30000000-0000-0000-0000-000000000001";

const SCENARIO_VALUES = Object.freeze({
  normal: Object.freeze([68, 69.5, 71, 72.25, 71.5, 70.75]),
  overheating: Object.freeze([72, 74, 77.5, 80, 83.5, 86]),
});

const MINUTE_MS = 60_000;

export function resolveAnchor(rawAnchor, now = new Date()) {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new Error("A valid current time is required");
  }

  const anchor =
    rawAnchor === undefined
      ? new Date(Math.floor(now.getTime() / MINUTE_MS) * MINUTE_MS)
      : parseIsoInstant(rawAnchor, { maxFractionDigits: 3 });

  if (anchor === undefined) {
    throw new Error(
      "The observation anchor must be a timezone-qualified ISO-8601 instant",
    );
  }

  if (anchor.getTime() > now.getTime()) {
    throw new Error("The observation anchor cannot be in the future");
  }

  return anchor;
}

export function buildScenario(scenarioName, anchor) {
  const values = SCENARIO_VALUES[scenarioName];

  if (values === undefined) {
    throw new Error("The scenario must be normal or overheating");
  }

  if (!(anchor instanceof Date) || !Number.isFinite(anchor.getTime())) {
    throw new Error("A valid observation anchor is required");
  }

  const compactAnchor = anchor
    .toISOString()
    .replaceAll("-", "")
    .replaceAll(":", "")
    .replaceAll(".", "");

  return {
    name: scenarioName,
    anchor: anchor.toISOString(),
    request: {
      idempotencyKey: `simulator:${scenarioName}:${compactAnchor}`,
      readings: values.map((value, index) => ({
        sensorId: NORTHSTAR_TEMPERATURE_SENSOR_ID,
        value,
        observedAt: new Date(
          anchor.getTime() - (values.length - 1 - index) * MINUTE_MS,
        ).toISOString(),
      })),
    },
  };
}
