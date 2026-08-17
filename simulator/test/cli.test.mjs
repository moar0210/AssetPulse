import assert from "node:assert/strict";
import test from "node:test";

import { runCli } from "../src/cli.mjs";

async function rejectsBeforeRequest(argv, environment, pattern) {
  let requests = 0;

  await assert.rejects(
    () =>
      runCli({
        argv,
        environment,
        now: new Date("2026-08-15T12:00:00Z"),
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
