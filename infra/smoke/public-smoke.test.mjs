import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import http from "node:http";
import { afterEach, test } from "node:test";

import {
  SmokeVerificationError,
  assertStrictTransportSecurity,
  normalizeBaseUrl,
  runCli,
  runPublicSmoke,
} from "./public-smoke.mjs";

const NORTHSTAR_ID = "00000000-0000-0000-0000-000000000001";
const RIVERSIDE_ID = "00000000-0000-0000-0000-000000000002";
const SESSION_COOKIE =
  "Path=/; Max-Age=1800; Secure; HttpOnly; SameSite=Strict";
const READY_EVENT_FRAME = "event:ready\ndata:{}\n\n";
const WEB_DOCUMENT = `<!doctype html>
<html><head><title>AssetPulse Lite</title>
<script type="module" crossorigin src="/assets/index-test.js"></script>
<link rel="stylesheet" crossorigin href="/assets/index-test.css">
</head><body><div id="root"></div></body></html>`;
const WEB_RESPONSES = {
  "/": { status: 200, contentType: "text/html", body: WEB_DOCUMENT },
  "/assets/index-test.js": {
    status: 200,
    contentType: "application/javascript",
    body: 'document.getElementById("root").textContent = "AssetPulse Lite";',
  },
  "/assets/index-test.css": {
    status: 200,
    contentType: "text/css",
    body: "body { color: #111; }",
  },
};
const OPENAPI_SECURITY_HEADERS = Object.freeze({
  "Content-Security-Policy":
    "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
  "Permissions-Policy": "camera=(), geolocation=(), microphone=()",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
});
const OPENAPI_DOCUMENT = await readFile(
  new URL("../../frontend/public/openapi.json", import.meta.url),
  "utf8",
);

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
  webResponses = {},
  leakCrossTenantAsset = false,
  openApiBody = OPENAPI_DOCUMENT,
  openApiCacheControl = "no-cache",
  openApiSecurityHeaders = OPENAPI_SECURITY_HEADERS,
  readinessFailures = 0,
  sseFrames = [READY_EVENT_FRAME, READY_EVENT_FRAME],
  endSseAfterFrame = false,
} = {}) {
  const state = {
    authenticated: false,
    healthChecks: 0,
    loginAccepted: false,
    openApiRequest: null,
    spoofRequest: null,
    streamConnections: 0,
    handlerError: null,
    webRequests: [],
  };

  const server = http.createServer((request, response) => {
    void (async () => {
      const url = new URL(request.url, "http://127.0.0.1");

      const webResponse =
        webResponses[url.pathname] ?? WEB_RESPONSES[url.pathname];
      if (request.method === "GET" && webResponse !== undefined) {
        state.webRequests.push({
          path: url.pathname,
          accept: request.headers.accept,
          cookie: request.headers.cookie ?? null,
        });
        response.writeHead(webResponse.status, {
          "Content-Type": webResponse.contentType,
        });
        if (webResponse.stall) {
          response.write(webResponse.body);
          return;
        }
        response.end(webResponse.body);
        return;
      }

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

      if (request.method === "GET" && url.pathname === "/openapi.json") {
        state.openApiRequest = {
          accept: request.headers.accept,
          authenticated: state.authenticated,
          cookie: request.headers.cookie ?? null,
        };
        const headers = {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(openApiBody),
          ...openApiSecurityHeaders,
        };
        if (openApiCacheControl !== null) {
          headers["Cache-Control"] = openApiCacheControl;
        }
        response.writeHead(200, headers);
        response.end(openApiBody);
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

      if (
        request.method === "GET" &&
        url.pathname === "/api/v1/alerts/stream"
      ) {
        if (!state.authenticated || !hasCookie(request, "authenticated")) {
          sendJson(response, 401, { code: "AUTHENTICATION_REQUIRED" });
          return;
        }

        const frame =
          sseFrames[state.streamConnections] ?? sseFrames.at(-1) ?? "";
        state.streamConnections += 1;
        response.writeHead(200, {
          "Content-Type": "text/event-stream",
          "Cache-Control": "no-store",
          Connection: "keep-alive",
        });
        response.write(frame);
        if (endSseAfterFrame) {
          response.end();
        }
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
  assert.deepEqual(application.state.webRequests, [
    { path: "/", accept: "text/html", cookie: null },
    {
      path: "/assets/index-test.js",
      accept: "text/javascript, application/javascript",
      cookie: null,
    },
    { path: "/assets/index-test.css", accept: "text/css", cookie: null },
  ]);
  assert.deepEqual(application.state.openApiRequest, {
    accept: "application/json",
    authenticated: false,
    cookie: null,
  });
  assert.equal(application.state.streamConnections, 2);
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
    "public-web-entry-and-assets",
    "public-openapi-contract",
    "csrf-and-secure-session-cookie",
    "seeded-northstar-login",
    "authenticated-csrf-rotation",
    "trusted-northstar-asset-scope",
    "alert-stream-ready-and-reconnect",
    "logout",
    "post-logout-assets-denied",
  ]);
});

for (const [name, path, overrides, message] of [
  ["missing web entry", "/", { status: 404 }, /expected HTTP 200/u],
  [
    "wrong document type",
    "/",
    { contentType: "application/json" },
    /Content-Type text\/html/u,
  ],
  [
    "placeholder page",
    "/",
    { body: "<html><body>Starting</body></html>" },
    /root element/u,
  ],
  [
    "missing built script",
    "/",
    { body: WEB_DOCUMENT.replace(/<script[^>]*><\/script>/u, "") },
    /module script and stylesheet/u,
  ],
  [
    "missing stylesheet",
    "/",
    { body: WEB_DOCUMENT.replace(/<link[^>]*>/u, "") },
    /module script and stylesheet/u,
  ],
  [
    "cross-origin script",
    "/",
    {
      body: WEB_DOCUMENT.replace(
        "/assets/index-test.js",
        "https://unexpected.example/assets/index-test.js",
      ),
    },
    /same-origin built asset/u,
  ],
  [
    "unexpected script path",
    "/",
    { body: WEB_DOCUMENT.replace("/assets/index-test.js", "/api/v1/session") },
    /same-origin built asset/u,
  ],
  [
    "missing script",
    "/assets/index-test.js",
    { status: 404 },
    /expected HTTP 200/u,
  ],
  [
    "redirecting script",
    "/assets/index-test.js",
    { status: 302 },
    /expected HTTP 200/u,
  ],
  [
    "missing stylesheet file",
    "/assets/index-test.css",
    { status: 404 },
    /expected HTTP 200/u,
  ],
  [
    "script SPA fallback",
    "/assets/index-test.js",
    { contentType: "text/html", body: WEB_DOCUMENT },
    /JavaScript Content-Type/u,
  ],
  [
    "stylesheet SPA fallback",
    "/assets/index-test.css",
    { contentType: "text/html", body: WEB_DOCUMENT },
    /Content-Type text\/css/u,
  ],
  ["empty script", "/assets/index-test.js", { body: " " }, /nonempty/u],
  ["empty stylesheet", "/assets/index-test.css", { body: "" }, /nonempty/u],
  [
    "mislabeled HTML script",
    "/assets/index-test.js",
    { body: WEB_DOCUMENT },
    /HTML fallback/u,
  ],
  [
    "oversized document",
    "/",
    { body: WEB_DOCUMENT + " ".repeat(65_536) },
    /exceeds 65536 bytes/u,
  ],
  [
    "oversized script",
    "/assets/index-test.js",
    { body: "x".repeat(2_097_153) },
    /exceeds 2097152 bytes/u,
  ],
  [
    "excess asset references",
    "/",
    {
      body: WEB_DOCUMENT.replace(
        "</head>",
        Array.from(
          { length: 16 },
          (_, index) =>
            `<link rel="stylesheet" href="/assets/extra-${index}.css">`,
        ).join("") + "</head>",
      ),
    },
    /at most 16/u,
  ],
]) {
  test(`the public smoke rejects ${name} before authentication`, async () => {
    const application = await startMockApplication({
      webResponses: { [path]: { ...WEB_RESPONSES[path], ...overrides } },
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
        error.check === "public-web-entry-and-assets" &&
        message.test(error.message),
    );
    assert.equal(application.state.loginAccepted, false);
  });
}

test("the public smoke accepts exact byte and asset-count bounds with alternate JavaScript media type", async () => {
  const extraLinks = Array.from(
    { length: 14 },
    (_, index) => `<link href='/assets/extra-${index}.css' rel='stylesheet'>`,
  ).join("");
  const document = WEB_DOCUMENT.replace("</head>", extraLinks + "</head>");
  const webResponses = {
    "/": { ...WEB_RESPONSES["/"], body: document.padEnd(65_536) },
    "/assets/index-test.js": {
      ...WEB_RESPONSES["/assets/index-test.js"],
      contentType: "text/javascript; charset=utf-8",
      body: "/*" + "x".repeat(2_097_148) + "*/",
    },
    ...Object.fromEntries(
      Array.from({ length: 14 }, (_, index) => [
        `/assets/extra-${index}.css`,
        WEB_RESPONSES["/assets/index-test.css"],
      ]),
    ),
  };
  const application = await startMockApplication({ webResponses });
  const result = await runPublicSmoke({
    baseUrl: application.baseUrl,
    allowHttp: true,
    readinessTimeoutMs: 1_000,
    readinessIntervalMs: 10,
    requestTimeoutMs: 1_000,
  });
  assert.ok(result.checks.includes("public-web-entry-and-assets"));
  assert.equal(application.state.webRequests.length, 17);
});

test("the public smoke bounds streamed UTF-8 bytes instead of character count", async () => {
  const application = await startMockApplication({
    webResponses: {
      "/assets/index-test.js": {
        ...WEB_RESPONSES["/assets/index-test.js"],
        body: "é".repeat(1_048_577),
      },
    },
  });
  await assert.rejects(
    runPublicSmoke({
      baseUrl: application.baseUrl,
      allowHttp: true,
      requestTimeoutMs: 1_000,
    }),
    (error) =>
      error instanceof SmokeVerificationError &&
      error.check === "public-web-entry-and-assets" &&
      /exceeds 2097152 bytes/u.test(error.message),
  );
  assert.equal(application.state.loginAccepted, false);
});

test("the public smoke deadline covers a stalled static body", async () => {
  const application = await startMockApplication({
    webResponses: {
      "/assets/index-test.css": {
        ...WEB_RESPONSES["/assets/index-test.css"],
        stall: true,
      },
    },
  });
  await assert.rejects(
    runPublicSmoke({
      baseUrl: application.baseUrl,
      allowHttp: true,
      requestTimeoutMs: 100,
    }),
    (error) =>
      error instanceof SmokeVerificationError &&
      error.check === "public-web-entry-and-assets" &&
      /within the request deadline/u.test(error.message),
  );
  assert.equal(application.state.loginAccepted, false);
});

test("the public smoke rejects malformed OpenAPI JSON", async () => {
  const application = await startMockApplication({ openApiBody: "{" });

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
      error.check === "public-openapi-contract" &&
      /valid JSON response body/u.test(error.message),
  );
});

test("the public smoke rejects an incomplete OpenAPI contract", async () => {
  const application = await startMockApplication({
    openApiBody: JSON.stringify({
      openapi: "3.1.0",
      info: { title: "Incomplete", version: "1.0.0" },
      paths: {},
    }),
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
      error.check === "public-openapi-contract" &&
      /failed OpenAPI contract validation/u.test(error.message),
  );
});

test("the public smoke rejects unsafe OpenAPI cache policies", async () => {
  for (const openApiCacheControl of [
    null,
    "public, max-age=31536000, immutable",
  ]) {
    const application = await startMockApplication({ openApiCacheControl });

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
        error.check === "public-openapi-contract" &&
        /Cache-Control to include no-store or no-cache/u.test(error.message),
    );
  }
});

test("the public smoke rejects missing OpenAPI static security headers", async () => {
  const application = await startMockApplication({
    openApiSecurityHeaders: {
      ...OPENAPI_SECURITY_HEADERS,
      "X-Content-Type-Options": "unsafe",
    },
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
      error.check === "public-openapi-contract" &&
      /expected x-content-type-options: nosniff/u.test(error.message),
  );
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
  assert.throws(
    () => normalizeBaseUrl("http://example.test", { allowHttp: true }),
    /only target a loopback origin/u,
  );
});

test("HTTPS responses require the exact bounded HSTS policy", () => {
  const exact = new Response(null, {
    headers: {
      "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    },
  });
  assert.doesNotThrow(() =>
    assertStrictTransportSecurity(exact, "https://assetpulse.example", "hsts"),
  );

  assert.throws(
    () =>
      assertStrictTransportSecurity(
        new Response(),
        "https://assetpulse.example",
        "hsts",
      ),
    (error) =>
      error instanceof SmokeVerificationError &&
      error.check === "hsts" &&
      /expected Strict-Transport-Security/u.test(error.message),
  );
  assert.throws(
    () =>
      assertStrictTransportSecurity(
        new Response(null, {
          headers: {
            "Strict-Transport-Security": "max-age=300; includeSubDomains",
          },
        }),
        "https://assetpulse.example",
        "hsts",
      ),
    (error) =>
      error instanceof SmokeVerificationError && error.check === "hsts",
  );

  assert.doesNotThrow(() =>
    assertStrictTransportSecurity(
      new Response(),
      "http://127.0.0.1:8080",
      "hsts",
    ),
  );
});

test("the public smoke rejects an incomplete ready event frame", async () => {
  const application = await startMockApplication({
    sseFrames: ["event:ready\ndata:{}\n"],
    endSseAfterFrame: true,
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
      error.check === "alert-stream-initial" &&
      /complete frame/u.test(error.message),
  );
});

test("the public smoke rejects a complete non-ready event frame", async () => {
  const application = await startMockApplication({
    sseFrames: ["event:heartbeat\ndata:{}\n\n"],
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
      error.check === "alert-stream-initial" &&
      /exact complete ready event frame/u.test(error.message),
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
