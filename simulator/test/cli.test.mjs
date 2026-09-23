import assert from "node:assert/strict";
import test from "node:test";

import { runCli } from "../src/cli.mjs";

async function rejectsBeforeRequest(
  argv,
  environment,
  pattern,
  now = new Date("2026-08-15T12:00:00Z"),
) {
  let requests = 0;

  await assert.rejects(
    () =>
      runCli({
        argv,
        environment,
        now,
        fetchImpl: async () => {
          requests += 1;
          throw new Error("Network should not be called");
        },
        write: () => {},
      }),
    pattern,
  );

  assert.equal(requests, 0);
}

test("rejects a future anchor before making a network request", async () => {
  let requests = 0;

  await assert.rejects(
    () =>
      runCli({
        argv: ["normal", "--at=2026-08-15T12:00:01Z"],
        environment: {},
        now: new Date("2026-08-15T12:00:00Z"),
        fetchImpl: async () => {
          requests += 1;
          throw new Error("Network should not be called");
        },
        write: () => {},
      }),
    /cannot be in the future/,
  );

  assert.equal(requests, 0);
});

test("rejects malformed CLI arguments before making a network request", async () => {
  for (const argv of [
    null,
    [],
    ["normal", 42],
    ["unknown"],
    ["normal", "--at"],
    ["normal", "--at="],
    ["normal", "--at=2026-08-15"],
    ["normal", "--at=2026-08-15T11:00:00Z", "extra"],
  ]) {
    await rejectsBeforeRequest(argv, {}, /scenario|argument|Usage|ISO-8601/);
  }
});

test("rejects malformed environment overrides before making a network request", async () => {
  await rejectsBeforeRequest(
    ["normal"],
    { ASSETPULSE_BASE_URL: "http://localhost:8080/unexpected" },
    /absolute HTTP origin/,
  );
  await rejectsBeforeRequest(
    ["normal"],
    { ASSETPULSE_EMAIL: 42 },
    /ASSETPULSE_EMAIL must be text/,
  );
  await rejectsBeforeRequest(["normal"], null, /process environment/);
});

for (const scenarioName of ["normal", "overheating"]) {
  test(`${scenarioName} rejects an unsupported fixed anchor before login`, async () => {
    for (const rawAnchor of [
      "0000-01-01T00:00:00Z",
      "0000-01-01T00:04:59.999Z",
      "0000-01-01T01:04:59.999+01:00",
      "0000-01-01T00:05:00+01:00",
      "9999-12-31T23:59:00-00:01",
    ]) {
      await rejectsBeforeRequest(
        [scenarioName, `--at=${rawAnchor}`],
        {},
        /supported telemetry range/,
        new Date("+010001-01-01T00:00:00Z"),
      );
    }
  });

  test(`${scenarioName} validates the default anchor after rounding`, async () => {
    await rejectsBeforeRequest(
      [scenarioName],
      {},
      /supported telemetry range/,
      new Date("0000-01-01T00:04:59.999Z"),
    );
  });
}
