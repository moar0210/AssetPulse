import assert from "node:assert/strict";
import test from "node:test";

import { createPublicApiClient } from "../src/client.mjs";
import { buildScenario } from "../src/scenarios.mjs";

const IDENTITY = {
  userId: "10000000-0000-0000-0000-000000000001",
  displayName: "Northstar Admin",
  email: "admin@northstar.example",
  organisation: {
    id: "00000000-0000-0000-0000-000000000001",
    slug: "northstar-operations",
    name: "Northstar Operations",
  },
  role: { code: "OPERATIONS_ADMIN", displayName: "Operations Admin" },
};

function jsonResponse(payload, status = 200, setCookie) {
  const headers = new Headers({ "Content-Type": "application/json" });
  for (const value of setCookie === undefined
    ? []
    : Array.isArray(setCookie)
      ? setCookie
      : [setCookie]) {
    headers.append("Set-Cookie", value);
  }
  return new Response(JSON.stringify(payload), { status, headers });
}

function queuedFetch(responses, calls) {
  return async (url, options) => {
    calls.push({ url: String(url), options });
    const response = responses.shift();
    assert.ok(response, "Unexpected simulator request");
    return response;
  };
}

test("accepts only an HTTP origin as the configured base URL", () => {
  const configuration = {
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: async () => {
      throw new Error("Network should not be called");
    },
  };

  assert.doesNotThrow(() =>
    createPublicApiClient({
      ...configuration,
      baseUrl: "https://assetpulse.example",
    }),
  );
  assert.doesNotThrow(() =>
    createPublicApiClient({
      ...configuration,
      baseUrl: "http://localhost:8080/",
    }),
  );

  for (const baseUrl of [
    " http://localhost:8080",
    "ftp://localhost:8080",
    "http://user:password@localhost:8080",
    "http://localhost:8080/api",
    "http://localhost:8080?",
    "http://localhost:8080#",
  ]) {
    assert.throws(
      () => createPublicApiClient({ ...configuration, baseUrl }),
      /absolute HTTP origin/,
    );
  }
});

test("uses the public session and telemetry API with rotated cookie and CSRF state", async () => {
  const scenario = buildScenario(
    "normal",
    new Date("2026-08-15T11:55:00.000Z"),
  );
  const calls = [];
  const responses = [
    jsonResponse(
      { headerName: "X-XSRF-TOKEN", token: "csrf-before-login" },
      200,
      [
        "ASSETPULSE_SESSION=session-before-login; Path=/; HttpOnly; SameSite=Strict",
        "OBSOLETE_SESSION=stale; Path=/; HttpOnly",
      ],
    ),
    jsonResponse(
      IDENTITY,
      200,
      "ASSETPULSE_SESSION=session-after-login; Path=/; HttpOnly; SameSite=Strict",
    ),
    jsonResponse(
      { headerName: "X-XSRF-TOKEN", token: "csrf-after-login" },
      200,
      [
        "ASSETPULSE_SESSION=session-after-csrf; Path=/; HttpOnly; SameSite=Strict",
        "OBSOLETE_SESSION=deleted; Max-Age=-1; Path=/; HttpOnly",
      ],
    ),
    jsonResponse(
      {
        batchId: "50000000-0000-0000-0000-000000000001",
        idempotencyKey: scenario.request.idempotencyKey,
        readingCount: scenario.request.readings.length,
        acceptedAt: "2026-08-15T11:55:01.123456Z",
      },
      200,
      "ASSETPULSE_SESSION=session-after-telemetry; Path=/; HttpOnly; SameSite=Strict",
    ),
    new Response(null, { status: 204 }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  const accepted = await client.run(scenario.request);

  assert.equal(accepted.readingCount, 6);
  assert.deepEqual(
    calls.map(({ url, options }) => [new URL(url).pathname, options.method]),
    [
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session", "POST"],
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/telemetry-batches", "POST"],
      ["/api/v1/session", "DELETE"],
    ],
  );

  const loginHeaders = calls[1].options.headers;
  assert.equal(
    loginHeaders.get("Cookie"),
    "ASSETPULSE_SESSION=session-before-login; OBSOLETE_SESSION=stale",
  );
  assert.equal(loginHeaders.get("X-XSRF-TOKEN"), "csrf-before-login");
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    email: "admin@northstar.example",
    password: "AssetPulse1!",
  });

  const telemetryHeaders = calls[3].options.headers;
  assert.equal(
    telemetryHeaders.get("Cookie"),
    "ASSETPULSE_SESSION=session-after-csrf",
  );
  assert.equal(telemetryHeaders.get("X-XSRF-TOKEN"), "csrf-after-login");
  assert.deepEqual(JSON.parse(calls[3].options.body), scenario.request);

  const logoutHeaders = calls[4].options.headers;
  assert.equal(
    logoutHeaders.get("Cookie"),
    "ASSETPULSE_SESSION=session-after-telemetry",
  );
  assert.equal(logoutHeaders.get("X-XSRF-TOKEN"), "csrf-after-login");
  assert.ok(calls.every(({ options }) => options.redirect === "error"));
});

test("does not expose response bodies or session material when telemetry fails", async () => {
  const scenario = buildScenario(
    "overheating",
    new Date("2026-08-15T11:55:00.000Z"),
  );
  const secretMarker = "must-not-appear";
  const calls = [];
  const responses = [
    jsonResponse(
      { headerName: "X-XSRF-TOKEN", token: secretMarker },
      200,
      `ASSETPULSE_SESSION=${secretMarker}; Path=/; HttpOnly`,
    ),
    jsonResponse(IDENTITY, 200),
    jsonResponse({ headerName: "X-XSRF-TOKEN", token: secretMarker }),
    jsonResponse({ detail: secretMarker }, 500),
    new Response(null, { status: 204 }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  await assert.rejects(
    () => client.run(scenario.request),
    (error) => {
      assert.equal(error.message, "Telemetry acceptance failed with HTTP 500");
      assert.equal(error.message.includes(secretMarker), false);
      return true;
    },
  );
  assert.equal(calls.at(-1).options.method, "DELETE");
});

test("rejects loose acceptance timestamps and still logs out", async () => {
  const scenario = buildScenario(
    "normal",
    new Date("2026-08-15T11:55:00.000Z"),
  );
  const calls = [];
  const responses = [
    jsonResponse({ headerName: "X-XSRF-TOKEN", token: "csrf-before-login" }),
    jsonResponse(IDENTITY),
    jsonResponse({ headerName: "X-XSRF-TOKEN", token: "csrf-after-login" }),
    jsonResponse({
      batchId: "50000000-0000-0000-0000-000000000001",
      idempotencyKey: scenario.request.idempotencyKey,
      readingCount: scenario.request.readings.length,
      acceptedAt: "2026-08-15",
    }),
    new Response(null, { status: 204 }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  await assert.rejects(
    () => client.run(scenario.request),
    /Telemetry acceptance returned an unexpected payload/,
  );
  assert.equal(calls.at(-1).options.method, "DELETE");
});

test("redacts an unsafe CSRF value before constructing request headers", async () => {
  const secretMarker = "must-not-appear";
  const calls = [];
  const responses = [
    jsonResponse({
      headerName: "X-XSRF-TOKEN",
      token: `valid-prefix\n${secretMarker}`,
    }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  await assert.rejects(
    () => client.run({ idempotencyKey: "unused", readings: [] }),
    (error) => {
      assert.equal(
        error.message,
        "The CSRF endpoint returned an unexpected payload",
      );
      assert.equal(error.message.includes(secretMarker), false);
      return true;
    },
  );
  assert.equal(calls.length, 1);
});

test("cleans up an authenticated session when the login body is malformed", async () => {
  const secretMarker = "must-not-appear";
  const calls = [];
  const responses = [
    jsonResponse({ headerName: "X-XSRF-TOKEN", token: "csrf-before-login" }),
    jsonResponse({ ...IDENTITY, unexpected: secretMarker }),
    jsonResponse({ headerName: "X-XSRF-TOKEN", token: "csrf-after-login" }),
    new Response(null, { status: 204 }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  await assert.rejects(
    () => client.run({ idempotencyKey: "unused", readings: [] }),
    (error) => {
      assert.equal(
        error.message,
        "Login returned an unexpected Operations Admin identity",
      );
      assert.equal(error.message.includes(secretMarker), false);
      return true;
    },
  );
  assert.deepEqual(
    calls.map(({ url, options }) => [new URL(url).pathname, options.method]),
    [
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session", "POST"],
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session", "DELETE"],
    ],
  );
  assert.equal(
    calls.at(-1).options.headers.get("X-XSRF-TOKEN"),
    "csrf-after-login",
  );
});

test("refreshes CSRF again for cleanup when the post-login refresh fails", async () => {
  const scenario = buildScenario(
    "normal",
    new Date("2026-08-15T11:55:00.000Z"),
  );
  const calls = [];
  const responses = [
    jsonResponse(
      { headerName: "X-XSRF-TOKEN", token: "csrf-before-login" },
      200,
      "ASSETPULSE_SESSION=session-before-login; Path=/; HttpOnly",
    ),
    jsonResponse(
      IDENTITY,
      200,
      "ASSETPULSE_SESSION=session-after-login; Path=/; HttpOnly",
    ),
    jsonResponse({}, 503),
    jsonResponse({
      headerName: "X-XSRF-TOKEN",
      token: "csrf-for-cleanup",
    }),
    new Response(null, { status: 204 }),
  ];
  const client = createPublicApiClient({
    baseUrl: "http://localhost:8080",
    email: "admin@northstar.example",
    password: "AssetPulse1!",
    fetchImpl: queuedFetch(responses, calls),
  });

  await assert.rejects(
    () => client.run(scenario.request),
    /CSRF bootstrap failed with HTTP 503/,
  );
  assert.deepEqual(
    calls.map(({ url, options }) => [new URL(url).pathname, options.method]),
    [
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session", "POST"],
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session/csrf", "GET"],
      ["/api/v1/session", "DELETE"],
    ],
  );
  assert.equal(
    calls.at(-1).options.headers.get("Cookie"),
    "ASSETPULSE_SESSION=session-after-login",
  );
  assert.equal(
    calls.at(-1).options.headers.get("X-XSRF-TOKEN"),
    "csrf-for-cleanup",
  );
});
