import process from "node:process";
import { pathToFileURL } from "node:url";

const HEALTH_BODY = "healthy\n";
const STATUS_BODY = '{"status":"available"}';
const JSON_MEDIA_TYPE = "application/json";
const SESSION_COOKIE = "ASSETPULSE_SESSION";
const NORTHSTAR_EMAIL = "admin@northstar.example";
const NORTHSTAR_PASSWORD = "AssetPulse1!";
const NORTHSTAR_ORGANISATION_ID = "00000000-0000-0000-0000-000000000001";
const RIVERSIDE_ORGANISATION_ID = "00000000-0000-0000-0000-000000000002";

const EXPECTED_IDENTITY = Object.freeze({
  userId: "10000000-0000-0000-0000-000000000001",
  displayName: "Nora Admin",
  email: NORTHSTAR_EMAIL,
  organisation: Object.freeze({
    id: NORTHSTAR_ORGANISATION_ID,
    slug: "northstar-operations",
    name: "Northstar Operations",
  }),
  role: Object.freeze({
    code: "OPERATIONS_ADMIN",
    displayName: "Operations Admin",
  }),
});

const EXPECTED_ASSETS = Object.freeze([
  Object.freeze({
    id: "20000000-0000-0000-0000-000000000001",
    assetCode: "PUMP-101",
    name: "Boiler Feed Pump",
  }),
  Object.freeze({
    id: "20000000-0000-0000-0000-000000000002",
    assetCode: "PUMP-102",
    name: "Cooling Water Pump",
  }),
]);

const CROSS_TENANT_MARKERS = Object.freeze([
  "PUMP-201",
  "20000000-0000-0000-0000-000000000003",
  "Process Pump",
]);

export class SmokeVerificationError extends Error {
  constructor(check, message, options) {
    super(`${check}: ${message}`, options);
    this.name = "SmokeVerificationError";
    this.check = check;
  }
}

function fail(check, message, options) {
  throw new SmokeVerificationError(check, message, options);
}

function exactKeys(value, expectedKeys) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }

  const actualKeys = Object.keys(value).sort();
  const sortedExpectedKeys = [...expectedKeys].sort();

  return (
    actualKeys.length === sortedExpectedKeys.length &&
    actualKeys.every((key, index) => key === sortedExpectedKeys[index])
  );
}

function mediaType(response) {
  return response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
}

function assertStatus(response, expectedStatus, check) {
  if (response.status !== expectedStatus) {
    fail(
      check,
      `expected HTTP ${expectedStatus}, received HTTP ${response.status}`,
    );
  }
}

function assertMediaType(response, expectedMediaType, check) {
  if (mediaType(response) !== expectedMediaType) {
    fail(check, `expected Content-Type ${expectedMediaType}`);
  }
}

function assertNoStore(response, check) {
  if (
    response.headers.get("cache-control")?.trim().toLowerCase() !== "no-store"
  ) {
    fail(check, "expected Cache-Control: no-store");
  }
}

function splitCombinedSetCookie(value) {
  if (!value) {
    return [];
  }

  return value
    .split(/,(?=\s*[^;,=\s]+=[^;,]*)/u)
    .map((cookie) => cookie.trim())
    .filter(Boolean);
}

export function getSetCookieHeaders(headers) {
  if (typeof headers.getSetCookie === "function") {
    return headers.getSetCookie();
  }

  return splitCombinedSetCookie(headers.get("set-cookie"));
}

export function parseSetCookie(setCookie) {
  const segments = setCookie.split(";").map((segment) => segment.trim());
  const nameValue = segments.shift();
  const separator = nameValue?.indexOf("=") ?? -1;

  if (separator <= 0) {
    return null;
  }

  const name = nameValue.slice(0, separator).trim();
  const value = nameValue.slice(separator + 1).trim();
  const attributes = new Map();

  for (const segment of segments) {
    if (!segment) {
      continue;
    }

    const attributeSeparator = segment.indexOf("=");
    const attributeName = (
      attributeSeparator === -1 ? segment : segment.slice(0, attributeSeparator)
    )
      .trim()
      .toLowerCase();
    const attributeValue =
      attributeSeparator === -1
        ? null
        : segment.slice(attributeSeparator + 1).trim();

    attributes.set(attributeName, attributeValue);
  }

  return { name, value, attributes };
}

export class CookieJar {
  #cookies = new Map();

  absorb(headers) {
    const parsedCookies = getSetCookieHeaders(headers)
      .map(parseSetCookie)
      .filter((cookie) => cookie !== null);

    for (const cookie of parsedCookies) {
      const maximumAge = Number.parseInt(
        cookie.attributes.get("max-age") ?? "",
        10,
      );
      const expired =
        cookie.value === "" ||
        (Number.isFinite(maximumAge) && maximumAge <= 0) ||
        (cookie.attributes.has("expires") &&
          Date.parse(cookie.attributes.get("expires")) <= Date.now());

      if (expired) {
        this.#cookies.delete(cookie.name);
      } else {
        this.#cookies.set(cookie.name, cookie.value);
      }
    }

    return parsedCookies;
  }

  header() {
    return [...this.#cookies.entries()]
      .map(([name, value]) => `${name}=${value}`)
      .join("; ");
  }
}

function assertSessionCookiePolicy(parsedCookies, check) {
  const sessionCookies = parsedCookies.filter(
    (cookie) => cookie.name === SESSION_COOKIE && cookie.value !== "",
  );

  if (sessionCookies.length === 0) {
    fail(check, `expected a ${SESSION_COOKIE} Set-Cookie header`);
  }

  for (const cookie of sessionCookies) {
    if (!cookie.attributes.has("secure")) {
      fail(check, `${SESSION_COOKIE} must include Secure`);
    }
    if (!cookie.attributes.has("httponly")) {
      fail(check, `${SESSION_COOKIE} must include HttpOnly`);
    }
    if (cookie.attributes.get("samesite")?.toLowerCase() !== "strict") {
      fail(check, `${SESSION_COOKIE} must include SameSite=Strict`);
    }
  }
}

export function normalizeBaseUrl(baseUrl, { allowHttp = false } = {}) {
  let parsed;

  try {
    parsed = new URL(baseUrl);
  } catch (cause) {
    fail("configuration", "base URL is not a valid absolute URL", { cause });
  }

  if (parsed.username || parsed.password) {
    fail("configuration", "base URL must not contain credentials");
  }
  if (parsed.search || parsed.hash) {
    fail(
      "configuration",
      "base URL must not contain a query string or fragment",
    );
  }
  if (parsed.pathname !== "/" && parsed.pathname !== "") {
    fail(
      "configuration",
      "base URL must identify the public origin, without a path",
    );
  }
  if (
    parsed.protocol !== "https:" &&
    !(allowHttp && parsed.protocol === "http:")
  ) {
    fail(
      "configuration",
      "base URL must use HTTPS (pass --allow-http only for an explicit local check)",
    );
  }

  return parsed.origin;
}

function positiveInteger(value, name) {
  const parsed = Number(value);

  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    fail("configuration", `${name} must be a positive integer`);
  }

  return parsed;
}

function parseBoolean(value, name) {
  if (value === undefined) {
    return false;
  }
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }

  fail("configuration", `${name} must be true or false`);
}

export function parseCliOptions(argv, env = process.env) {
  let baseUrl;
  let allowHttp = parseBoolean(
    env.ASSETPULSE_SMOKE_ALLOW_HTTP,
    "ASSETPULSE_SMOKE_ALLOW_HTTP",
  );
  let readinessTimeoutMs = positiveInteger(
    env.ASSETPULSE_SMOKE_READINESS_TIMEOUT_MS ?? 120_000,
    "readiness timeout",
  );
  let readinessIntervalMs = positiveInteger(
    env.ASSETPULSE_SMOKE_READINESS_INTERVAL_MS ?? 2_000,
    "readiness interval",
  );
  let requestTimeoutMs = positiveInteger(
    env.ASSETPULSE_SMOKE_REQUEST_TIMEOUT_MS ?? 10_000,
    "request timeout",
  );
  let help = false;

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];

    if (argument === "--help" || argument === "-h") {
      help = true;
    } else if (argument === "--allow-http") {
      allowHttp = true;
    } else if (argument === "--base-url") {
      baseUrl = argv[(index += 1)];
    } else if (argument.startsWith("--base-url=")) {
      baseUrl = argument.slice("--base-url=".length);
    } else if (argument === "--readiness-timeout-ms") {
      readinessTimeoutMs = positiveInteger(
        argv[(index += 1)],
        "readiness timeout",
      );
    } else if (argument === "--readiness-interval-ms") {
      readinessIntervalMs = positiveInteger(
        argv[(index += 1)],
        "readiness interval",
      );
    } else if (argument === "--request-timeout-ms") {
      requestTimeoutMs = positiveInteger(argv[(index += 1)], "request timeout");
    } else if (argument?.startsWith("-")) {
      fail("configuration", `unknown option ${argument}`);
    } else if (baseUrl === undefined) {
      baseUrl = argument;
    } else {
      fail("configuration", "only one base URL may be supplied");
    }
  }

  if (help) {
    return { help: true };
  }

  baseUrl ??=
    env.ASSETPULSE_SMOKE_BASE_URL ?? env.ASSETPULSE_BASE_URL ?? undefined;

  if (!baseUrl) {
    fail("configuration", "base URL is required");
  }

  const password = env.ASSETPULSE_SMOKE_PASSWORD ?? NORTHSTAR_PASSWORD;
  if (typeof password !== "string" || password.length === 0) {
    fail("configuration", "smoke password must not be empty");
  }

  return {
    help: false,
    baseUrl,
    allowHttp,
    readinessTimeoutMs,
    readinessIntervalMs,
    requestTimeoutMs,
    password,
  };
}

const USAGE = `Usage: node infra/smoke/public-smoke.mjs --base-url <public-origin> [options]

Options:
  --allow-http                    Allow HTTP for an explicit local check only
  --readiness-timeout-ms <ms>    Readiness deadline (default: 120000)
  --readiness-interval-ms <ms>   Delay between readiness attempts (default: 2000)
  --request-timeout-ms <ms>      Per-request deadline (default: 10000)

Environment:
  ASSETPULSE_SMOKE_BASE_URL
  ASSETPULSE_SMOKE_PASSWORD
  ASSETPULSE_SMOKE_ALLOW_HTTP
  ASSETPULSE_SMOKE_READINESS_TIMEOUT_MS
  ASSETPULSE_SMOKE_READINESS_INTERVAL_MS
  ASSETPULSE_SMOKE_REQUEST_TIMEOUT_MS`;

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function fetchResponse(
  fetchImpl,
  url,
  { check, requestTimeoutMs, cookieJar, headers, ...options },
) {
  const requestHeaders = new Headers(headers);
  const cookieHeader = cookieJar?.header();

  if (cookieHeader && !requestHeaders.has("cookie")) {
    requestHeaders.set("Cookie", cookieHeader);
  }

  let response;
  try {
    response = await fetchImpl(url, {
      ...options,
      headers: requestHeaders,
      redirect: "manual",
      signal: AbortSignal.timeout(requestTimeoutMs),
    });
  } catch (cause) {
    fail(check, "request failed", { cause });
  }

  const parsedCookies = cookieJar?.absorb(response.headers) ?? [];
  return { response, parsedCookies };
}

async function exactPublicEndpoints(baseUrl, fetchImpl, requestTimeoutMs) {
  const health = await fetchResponse(fetchImpl, `${baseUrl}/healthz`, {
    check: "health",
    requestTimeoutMs,
    method: "GET",
    headers: { Accept: "text/plain" },
  });
  assertStatus(health.response, 200, "health");
  assertMediaType(health.response, "text/plain", "health");
  if ((await health.response.text()) !== HEALTH_BODY) {
    fail("health", 'expected the exact body "healthy\\n"');
  }

  const status = await fetchResponse(fetchImpl, `${baseUrl}/api/v1/status`, {
    check: "status",
    requestTimeoutMs,
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
  });
  assertStatus(status.response, 200, "status");
  assertMediaType(status.response, JSON_MEDIA_TYPE, "status");
  if ((await status.response.text()) !== STATUS_BODY) {
    fail("status", `expected the exact body ${STATUS_BODY}`);
  }
}

export async function waitForReadiness({
  baseUrl,
  fetchImpl = globalThis.fetch,
  readinessTimeoutMs = 120_000,
  readinessIntervalMs = 2_000,
  requestTimeoutMs = 10_000,
  sleep = delay,
}) {
  const deadline = Date.now() + readinessTimeoutMs;
  let lastError;

  do {
    try {
      await exactPublicEndpoints(baseUrl, fetchImpl, requestTimeoutMs);
      return;
    } catch (error) {
      lastError = error;
    }

    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      break;
    }
    await sleep(Math.min(readinessIntervalMs, remaining));
  } while (Date.now() <= deadline);

  fail(
    "readiness",
    `public application did not become ready within ${readinessTimeoutMs} ms${
      lastError instanceof Error ? ` (${lastError.message})` : ""
    }`,
    lastError === undefined ? undefined : { cause: lastError },
  );
}

async function readJson(
  response,
  expectedStatus,
  check,
  { noStore = false } = {},
) {
  assertStatus(response, expectedStatus, check);
  assertMediaType(response, JSON_MEDIA_TYPE, check);
  if (noStore) {
    assertNoStore(response, check);
  }

  try {
    return JSON.parse(await response.text());
  } catch (cause) {
    fail(check, "expected a valid JSON response body", { cause });
  }
}

function assertCsrfPayload(payload, check) {
  if (
    !exactKeys(payload, ["headerName", "token"]) ||
    payload.headerName !== "X-CSRF-TOKEN" ||
    typeof payload.token !== "string" ||
    payload.token.length === 0
  ) {
    fail(check, "received an unexpected CSRF bootstrap payload");
  }
}

function assertExactIdentity(payload) {
  if (
    !exactKeys(payload, [
      "userId",
      "displayName",
      "email",
      "organisation",
      "role",
    ]) ||
    !exactKeys(payload.organisation, ["id", "slug", "name"]) ||
    !exactKeys(payload.role, ["code", "displayName"]) ||
    payload.userId !== EXPECTED_IDENTITY.userId ||
    payload.displayName !== EXPECTED_IDENTITY.displayName ||
    payload.email !== EXPECTED_IDENTITY.email ||
    payload.organisation.id !== EXPECTED_IDENTITY.organisation.id ||
    payload.organisation.slug !== EXPECTED_IDENTITY.organisation.slug ||
    payload.organisation.name !== EXPECTED_IDENTITY.organisation.name ||
    payload.role.code !== EXPECTED_IDENTITY.role.code ||
    payload.role.displayName !== EXPECTED_IDENTITY.role.displayName
  ) {
    fail("login", "seeded Northstar admin identity did not match exactly");
  }
}

function assertExactAssets(payload) {
  const serialized = JSON.stringify(payload);
  const leakedMarker = CROSS_TENANT_MARKERS.find((marker) =>
    serialized.includes(marker),
  );

  if (leakedMarker) {
    fail(
      "assets",
      `cross-tenant Riverside asset marker ${leakedMarker} was present`,
    );
  }

  if (!exactKeys(payload, ["assets"]) || !Array.isArray(payload.assets)) {
    fail("assets", "received an unexpected asset-list response shape");
  }
  if (payload.assets.length !== EXPECTED_ASSETS.length) {
    fail("assets", "expected exactly the two seeded Northstar assets");
  }

  for (let index = 0; index < EXPECTED_ASSETS.length; index += 1) {
    const actual = payload.assets[index];
    const expected = EXPECTED_ASSETS[index];

    if (
      !exactKeys(actual, ["id", "assetCode", "name"]) ||
      actual.id !== expected.id ||
      actual.assetCode !== expected.assetCode ||
      actual.name !== expected.name
    ) {
      fail(
        "assets",
        "expected the exact ordered PUMP-101 and PUMP-102 summaries",
      );
    }
  }
}

async function csrfExchange(
  baseUrl,
  fetchImpl,
  requestTimeoutMs,
  cookieJar,
  check,
) {
  const { response, parsedCookies } = await fetchResponse(
    fetchImpl,
    `${baseUrl}/api/v1/session/csrf`,
    {
      check,
      requestTimeoutMs,
      cookieJar,
      method: "GET",
      headers: { Accept: JSON_MEDIA_TYPE },
    },
  );
  const payload = await readJson(response, 200, check, { noStore: true });
  assertCsrfPayload(payload, check);
  return { ...payload, parsedCookies };
}

export async function runPublicSmoke({
  baseUrl,
  password = NORTHSTAR_PASSWORD,
  allowHttp = false,
  fetchImpl = globalThis.fetch,
  readinessTimeoutMs = 120_000,
  readinessIntervalMs = 2_000,
  requestTimeoutMs = 10_000,
  sleep = delay,
  onCheck = () => {},
} = {}) {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl, { allowHttp });
  if (typeof fetchImpl !== "function") {
    fail("configuration", "fetch implementation is unavailable");
  }
  if (typeof password !== "string" || password.length === 0) {
    fail("configuration", "smoke password must not be empty");
  }

  const completedChecks = [];
  const complete = (check) => {
    completedChecks.push(check);
    onCheck(check);
  };

  await waitForReadiness({
    baseUrl: normalizedBaseUrl,
    fetchImpl,
    readinessTimeoutMs: positiveInteger(
      readinessTimeoutMs,
      "readiness timeout",
    ),
    readinessIntervalMs: positiveInteger(
      readinessIntervalMs,
      "readiness interval",
    ),
    requestTimeoutMs: positiveInteger(requestTimeoutMs, "request timeout"),
    sleep,
  });
  complete("readiness-and-public-endpoints");

  const cookieJar = new CookieJar();
  const anonymousCsrf = await csrfExchange(
    normalizedBaseUrl,
    fetchImpl,
    requestTimeoutMs,
    cookieJar,
    "csrf-bootstrap",
  );
  assertSessionCookiePolicy(anonymousCsrf.parsedCookies, "csrf-cookie");
  complete("csrf-and-secure-session-cookie");

  const login = await fetchResponse(
    fetchImpl,
    `${normalizedBaseUrl}/api/v1/session`,
    {
      check: "login",
      requestTimeoutMs,
      cookieJar,
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [anonymousCsrf.headerName]: anonymousCsrf.token,
      },
      body: JSON.stringify({ email: NORTHSTAR_EMAIL, password }),
    },
  );
  const identity = await readJson(login.response, 200, "login", {
    noStore: true,
  });
  assertExactIdentity(identity);
  if (login.parsedCookies.some((cookie) => cookie.name === SESSION_COOKIE)) {
    assertSessionCookiePolicy(login.parsedCookies, "login-cookie");
  }
  complete("seeded-northstar-login");

  const authenticatedCsrf = await csrfExchange(
    normalizedBaseUrl,
    fetchImpl,
    requestTimeoutMs,
    cookieJar,
    "authenticated-csrf",
  );
  if (authenticatedCsrf.token === anonymousCsrf.token) {
    fail("authenticated-csrf", "CSRF token did not rotate after login");
  }
  complete("authenticated-csrf-rotation");

  const spoofedAssetsUrl = new URL("/api/v1/assets", normalizedBaseUrl);
  spoofedAssetsUrl.searchParams.set(
    "organisationId",
    RIVERSIDE_ORGANISATION_ID,
  );
  spoofedAssetsUrl.searchParams.set("organisation", "riverside-manufacturing");
  spoofedAssetsUrl.searchParams.set("tenantId", RIVERSIDE_ORGANISATION_ID);
  spoofedAssetsUrl.searchParams.set("tenant", "riverside-manufacturing");

  const assets = await fetchResponse(fetchImpl, spoofedAssetsUrl, {
    check: "assets",
    requestTimeoutMs,
    cookieJar,
    method: "GET",
    headers: {
      Accept: JSON_MEDIA_TYPE,
      "X-Organisation-ID": RIVERSIDE_ORGANISATION_ID,
      "X-Organisation": "riverside-manufacturing",
      "X-Tenant-ID": RIVERSIDE_ORGANISATION_ID,
      "X-Tenant": "riverside-manufacturing",
      "X-Role": "OPERATIONS_ADMIN",
    },
  });
  const assetPayload = await readJson(assets.response, 200, "assets", {
    noStore: true,
  });
  assertExactAssets(assetPayload);
  complete("trusted-northstar-asset-scope");

  const preLogoutCookie = cookieJar.header();
  const logout = await fetchResponse(
    fetchImpl,
    `${normalizedBaseUrl}/api/v1/session`,
    {
      check: "logout",
      requestTimeoutMs,
      cookieJar,
      method: "DELETE",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        [authenticatedCsrf.headerName]: authenticatedCsrf.token,
      },
    },
  );
  assertStatus(logout.response, 204, "logout");
  if ((await logout.response.text()) !== "") {
    fail("logout", "expected an empty HTTP 204 response body");
  }
  complete("logout");

  const afterLogout = await fetchResponse(
    fetchImpl,
    `${normalizedBaseUrl}/api/v1/assets`,
    {
      check: "post-logout-assets",
      requestTimeoutMs,
      method: "GET",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        Cookie: preLogoutCookie,
      },
    },
  );
  assertStatus(afterLogout.response, 401, "post-logout-assets");
  await afterLogout.response.arrayBuffer();
  complete("post-logout-assets-denied");

  return Object.freeze({
    baseUrl: normalizedBaseUrl,
    checks: Object.freeze([...completedChecks]),
  });
}

export const verifyPublicSmoke = runPublicSmoke;

export function redactSecrets(value, secrets) {
  let redacted = String(value);

  for (const secret of secrets) {
    if (typeof secret === "string" && secret.length > 0) {
      redacted = redacted.split(secret).join("[REDACTED]");
    }
  }

  return redacted;
}

export async function runCli({
  argv = process.argv.slice(2),
  env = process.env,
  logger = console,
  runSmoke = runPublicSmoke,
} = {}) {
  let options;

  try {
    options = parseCliOptions(argv, env);
    if (options.help) {
      logger.log(USAGE);
      return 0;
    }

    const result = await runSmoke(options);
    logger.log(
      redactSecrets(
        `Public smoke verification passed (${result.checks.length} checks) for ${result.baseUrl}`,
        [options.password],
      ),
    );
    return 0;
  } catch (error) {
    const message =
      error instanceof Error ? error.message : "unexpected failure";
    const password = options?.password ?? env.ASSETPULSE_SMOKE_PASSWORD;
    logger.error(
      `Public smoke verification failed: ${redactSecrets(message, [password])}`,
    );
    return 1;
  }
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) {
  process.exitCode = await runCli();
}
