import assert from "node:assert/strict";
import http from "node:http";
import { afterEach, test } from "node:test";

import {
  SmokeVerificationError,
  normalizeBaseUrl,
  runCli,
  runPublicSmoke,
} from "./public-smoke.mjs";

const NORTHSTAR_ID = "00000000-0000-0000-0000-000000000001";
const RIVERSIDE_ID = "00000000-0000-0000-0000-000000000002";
const SESSION_COOKIE =
  "Path=/; Max-Age=1800; Secure; HttpOnly; SameSite=Strict";

const IDENTITY = {
  userId: "10000000-0000-0000-0000-000000000001",
  displayName: "Nora Admin",
  email: "admin@northstar.example",
  organisation: {
    id: NORTHSTAR_ID,
    slug: "northstar-operations",
    name: "Northstar Operations",
  },
  role: {
    code: "OPERATIONS_ADMIN",
    displayName: "Operations Admin",
  },
};

const NORTHSTAR_ASSETS = [
  {
    id: "20000000-0000-0000-0000-000000000001",
    assetCode: "PUMP-101",
    name: "Boiler Feed Pump",
  },
  {
    id: "20000000-0000-0000-0000-000000000002",
    assetCode: "PUMP-102",
    name: "Cooling Water Pump",
  },
];

const openServers = new Set();

afterEach(async () => {
  await Promise.all(
    [...openServers].map(
      (server) =>
        new Promise((resolve, reject) => {
          server.close((error) => (error ? reject(error) : resolve()));
        }),
    ),
  );
  openServers.clear();
});

function sendJson(response, status, payload, headers = {}) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
    ...headers,
  });
  response.end(body);
}

async function readJsonBody(request) {
  const chunks = [];
  for await (const chunk of request) {
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function hasCookie(request, value) {
  return request.headers.cookie
    ?.split(";")
    .map((cookie) => cookie.trim())
    .includes(`ASSETPULSE_SESSION=${value}`);
}

async function startMockApplication({
  leakCrossTenantAsset = false,
  readinessFailures = 0,
} = {}) {
  const state = {
    authenticated: false,
    healthChecks: 0,
    loginAccepted: false,
    spoofRequest: null,
    handlerError: null,
  };

  const server = http.createServer((request, response) => {
    void (async () => {
      const url = new URL(request.url, "http://127.0.0.1");

      if (request.method === "GET" && url.pathname === "/healthz") {
        state.healthChecks += 1;
        if (state.healthChecks <= readinessFailures) {
          response.writeHead(503, { "Content-Type": "text/plain" });
          response.end("starting\n");
          return;
        }
        response.writeHead(200, { "Content-Type": "text/plain" });
        response.end("healthy\n");
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/v1/status") {
        sendJson(response, 200, { status: "available" });
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/v1/session/csrf") {
        if (state.authenticated && hasCookie(request, "authenticated")) {
          sendJson(
            response,
            200,
            { headerName: "X-CSRF-TOKEN", token: "authenticated-token" },
            { "Cache-Control": "no-store" },
          );
        } else {
          sendJson(
            response,
            200,
            { headerName: "X-CSRF-TOKEN", token: "anonymous-token" },
            {
              "Cache-Control": "no-store",
              "Set-Cookie": `ASSETPULSE_SESSION=anonymous; ${SESSION_COOKIE}`,
            },
          );
        }
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/v1/session") {
        const body = await readJsonBody(request);
        state.loginAccepted =
          hasCookie(request, "anonymous") &&
          request.headers["x-csrf-token"] === "anonymous-token" &&
          body.email === "admin@northstar.example" &&
          body.password === "AssetPulse1!" &&
          Object.keys(body).length === 2;

        if (!state.loginAccepted) {
          sendJson(response, 401, { code: "AUTHENTICATION_FAILED" });
          return;
        }

        state.authenticated = true;
        sendJson(response, 200, IDENTITY, {
          "Cache-Control": "no-store",
          "Set-Cookie": `ASSETPULSE_SESSION=authenticated; ${SESSION_COOKIE}`,
        });
        return;
      }

      if (request.method === "GET" && url.pathname === "/api/v1/assets") {
        if (!state.authenticated || !hasCookie(request, "authenticated")) {
          sendJson(response, 401, { code: "AUTHENTICATION_REQUIRED" });
          return;
        }

        state.spoofRequest = {
          organisationId: url.searchParams.get("organisationId"),
          organisation: url.searchParams.get("organisation"),
          tenantId: url.searchParams.get("tenantId"),
          tenant: url.searchParams.get("tenant"),
          organisationIdHeader: request.headers["x-organisation-id"],
          organisationHeader: request.headers["x-organisation"],
          tenantIdHeader: request.headers["x-tenant-id"],
          tenantHeader: request.headers["x-tenant"],
          roleHeader: request.headers["x-role"],
        };

        const assets = leakCrossTenantAsset
          ? [
              ...NORTHSTAR_ASSETS,
              {
                id: "20000000-0000-0000-0000-000000000003",
                assetCode: "PUMP-201",
                name: "Process Pump",
              },
            ]
          : NORTHSTAR_ASSETS;
        sendJson(response, 200, { assets }, { "Cache-Control": "no-store" });
        return;
      }

      if (request.method === "DELETE" && url.pathname === "/api/v1/session") {
        if (
          !state.authenticated ||
          !hasCookie(request, "authenticated") ||
          request.headers["x-csrf-token"] !== "authenticated-token"
        ) {
          sendJson(response, 403, { code: "CSRF_REJECTED" });
          return;
        }

        state.authenticated = false;
        response.writeHead(204, {
          "Set-Cookie":
            "ASSETPULSE_SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
        });
        response.end();
        return;
      }

      response.writeHead(404);
      response.end();
    })().catch((error) => {
      state.handlerError = error;
      if (!response.headersSent) {
        response.writeHead(500);
      }
      response.end();
    });
  });

  openServers.add(server);
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });

  const address = server.address();
  assert.equal(typeof address, "object");
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    server,
    state,
  };
}

test("the public smoke journey succeeds and sends direct tenant spoof markers", async () => {
  const application = await startMockApplication({ readinessFailures: 1 });
  const result = await runPublicSmoke({
    baseUrl: application.baseUrl,
    allowHttp: true,
    readinessTimeoutMs: 1_000,
    readinessIntervalMs: 10,
    requestTimeoutMs: 1_000,
  });

  assert.equal(application.state.handlerError, null);
  assert.equal(application.state.healthChecks, 2);
  assert.equal(application.state.loginAccepted, true);
  assert.deepEqual(application.state.spoofRequest, {
    organisationId: RIVERSIDE_ID,
    organisation: "riverside-manufacturing",
    tenantId: RIVERSIDE_ID,
    tenant: "riverside-manufacturing",
    organisationIdHeader: RIVERSIDE_ID,
    organisationHeader: "riverside-manufacturing",
    tenantIdHeader: RIVERSIDE_ID,
    tenantHeader: "riverside-manufacturing",
    roleHeader: "OPERATIONS_ADMIN",
  });
  assert.deepEqual(result.checks, [
    "readiness-and-public-endpoints",
    "csrf-and-secure-session-cookie",
    "seeded-northstar-login",
    "authenticated-csrf-rotation",
    "trusted-northstar-asset-scope",
    "logout",
    "post-logout-assets-denied",
  ]);
});

test("the public smoke journey fails on a Riverside cross-tenant leak", async () => {
  const application = await startMockApplication({
    leakCrossTenantAsset: true,
  });

  await assert.rejects(
    runPublicSmoke({
      baseUrl: application.baseUrl,
      allowHttp: true,
      readinessTimeoutMs: 1_000,
      readinessIntervalMs: 10,
      requestTimeoutMs: 1_000,
    }),
    (error) =>
      error instanceof SmokeVerificationError &&
      error.check === "assets" &&
      /PUMP-201/u.test(error.message),
  );
});

test("HTTP is rejected unless the local override is explicit", () => {
  assert.throws(
    () => normalizeBaseUrl("http://127.0.0.1:8080"),
    /must use HTTPS/u,
  );
  assert.equal(
    normalizeBaseUrl("http://127.0.0.1:8080", { allowHttp: true }),
    "http://127.0.0.1:8080",
  );
});

test("the CLI never writes the supplied password to output", async () => {
  const password = "NeverWriteThisPassword-42";
  const output = [];
  const exitCode = await runCli({
    argv: ["--base-url", "https://assetpulse.example"],
    env: { ASSETPULSE_SMOKE_PASSWORD: password },
    logger: {
      log: (message) => output.push(message),
      error: (message) => output.push(message),
    },
    runSmoke: async () => {
      throw new Error(`simulated failure containing ${password}`);
    },
  });

  assert.equal(exitCode, 1);
  assert.equal(output.join("\n").includes(password), false);
  assert.match(output.join("\n"), /\[REDACTED\]/u);
});
