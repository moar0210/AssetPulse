import http from "k6/http";
import execution from "k6/execution";
import { check, fail, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

import {
  captureMatchingAlertBaseline,
  evidenceWindowFollowsBaseline,
  hasCausalAlertAdvance,
} from "./alert-evidence.mjs";

const JSON_MEDIA_TYPE = "application/json";
const SENSOR_ID = "30000000-0000-0000-0000-000000000001";
const RULE_CODE = "PUMP-101-HIGH-TEMP";
const RULE_THRESHOLD_VALUE = 80;
const TELEMETRY_VALUES = [72, 74, 77.5, 80, 83.5, 86];
const EXPECTED_ALERT_OCCURRENCE_DELTA = TELEMETRY_VALUES.filter(
  (value) => value >= RULE_THRESHOLD_VALUE,
).length;
const DEFAULT_EMAIL = "admin@northstar.example";
const MAX_VUS = 1;
const MAX_ITERATIONS = 20;
const MAX_REQUEST_TIMEOUT_SECONDS = 30;
const MAX_ALERT_WAIT_SECONDS = 60;
const DEFAULT_SUMMARY_PATH = "k6-telemetry-to-alert-summary.json";
const SUMMARY_PATH_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$/;
const RUN_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,39}$/;

const scenarioFailures = new Counter("assetpulse_scenario_failures");
const scenarioSuccess = new Rate("assetpulse_scenario_success");
const telemetryAcceptLatency = new Trend(
  "assetpulse_telemetry_accept_latency",
  true,
);
const telemetryToAlertLatency = new Trend(
  "assetpulse_telemetry_to_alert_latency",
  true,
);

function boundedInteger(name, defaultValue, maximum) {
  const rawValue = __ENV[name] ?? String(defaultValue);
  const value = Number(rawValue);

  if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
    throw new Error(`${name} must be an integer from 1 through ${maximum}`);
  }

  return value;
}

function booleanEnvironment(name, defaultValue = false) {
  const rawValue = __ENV[name];
  if (rawValue === undefined) {
    return defaultValue;
  }
  if (rawValue === "true") {
    return true;
  }
  if (rawValue === "false") {
    return false;
  }
  throw new Error(`${name} must be true or false`);
}

function nonEmptyEnvironment(name, defaultValue) {
  const value = __ENV[name] ?? defaultValue;
  if (
    typeof value !== "string" ||
    value.length === 0 ||
    value.trim() !== value
  ) {
    throw new Error(
      `${name} must be non-empty and must not have outer whitespace`,
    );
  }
  return value;
}

function normalizedBaseUrl() {
  const rawValue = nonEmptyEnvironment("ASSETPULSE_BASE_URL");
  let parsed;
  try {
    parsed = new URL(rawValue);
  } catch {
    throw new Error("ASSETPULSE_BASE_URL must be an absolute origin");
  }

  const allowHttp = booleanEnvironment("ASSETPULSE_K6_ALLOW_HTTP");
  if (
    parsed.username !== "" ||
    parsed.password !== "" ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    (parsed.pathname !== "" && parsed.pathname !== "/") ||
    (parsed.protocol !== "https:" &&
      !(allowHttp && parsed.protocol === "http:"))
  ) {
    throw new Error(
      "ASSETPULSE_BASE_URL must be a credential-free HTTPS origin (HTTP is local-only)",
    );
  }

  if (
    allowHttp &&
    !["localhost", "127.0.0.1", "::1", "[::1]"].includes(parsed.hostname)
  ) {
    throw new Error(
      "ASSETPULSE_K6_ALLOW_HTTP may only be used with a loopback origin",
    );
  }

  return parsed.origin;
}

function safeOptionalEnvironment(name, pattern, maximumLength) {
  const value = __ENV[name];
  if (value === undefined) {
    return null;
  }
  if (
    value.length === 0 ||
    value.length > maximumLength ||
    value.trim() !== value ||
    !pattern.test(value)
  ) {
    throw new Error(`${name} has an invalid value`);
  }
  return value;
}

const BASE_URL = normalizedBaseUrl();
const EMAIL = nonEmptyEnvironment("ASSETPULSE_K6_EMAIL", DEFAULT_EMAIL);
const PASSWORD = nonEmptyEnvironment("ASSETPULSE_K6_PASSWORD");
const VUS = boundedInteger("ASSETPULSE_K6_VUS", 1, MAX_VUS);
const ITERATIONS = boundedInteger(
  "ASSETPULSE_K6_ITERATIONS",
  3,
  MAX_ITERATIONS,
);
const REQUEST_TIMEOUT_SECONDS = boundedInteger(
  "ASSETPULSE_K6_REQUEST_TIMEOUT_SECONDS",
  10,
  MAX_REQUEST_TIMEOUT_SECONDS,
);
const ALERT_WAIT_SECONDS = boundedInteger(
  "ASSETPULSE_K6_ALERT_WAIT_SECONDS",
  30,
  MAX_ALERT_WAIT_SECONDS,
);
const RUN_ID =
  safeOptionalEnvironment("ASSETPULSE_K6_RUN_ID", RUN_ID_PATTERN, 40) ??
  `run-${Date.now()}`;
const SUMMARY_PATH =
  safeOptionalEnvironment(
    "ASSETPULSE_K6_SUMMARY_PATH",
    SUMMARY_PATH_PATTERN,
    200,
  ) ?? DEFAULT_SUMMARY_PATH;
const ENVIRONMENT_NAME =
  safeOptionalEnvironment(
    "ASSETPULSE_K6_ENVIRONMENT",
    /^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/,
    40,
  ) ?? "unspecified";
const DEPLOY_REVISION = safeOptionalEnvironment(
  "ASSETPULSE_K6_DEPLOY_REVISION",
  /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/,
  100,
);

if (
  SUMMARY_PATH.startsWith("/") ||
  SUMMARY_PATH.includes("\\") ||
  SUMMARY_PATH.split("/").includes("..") ||
  ["stdout", "stderr"].includes(SUMMARY_PATH)
) {
  throw new Error("ASSETPULSE_K6_SUMMARY_PATH must be a relative safe path");
}

export const options = {
  scenarios: {
    telemetry_to_alert: {
      executor: "shared-iterations",
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: "3m",
      gracefulStop: "5s",
    },
  },
  thresholds: {
    assetpulse_scenario_success: ["rate==1"],
    assetpulse_telemetry_accept_latency: ["p(95)<5000"],
    assetpulse_telemetry_to_alert_latency: ["p(95)<30000"],
    http_req_failed: ["rate<0.01"],
  },
};

function requestParameters(name, headers = {}) {
  return {
    headers,
    redirects: 0,
    timeout: `${REQUEST_TIMEOUT_SECONDS}s`,
    tags: { name },
  };
}

function requireStatus(response, expectedStatus, operation) {
  const passed = check(response, {
    [`${operation} returns HTTP ${expectedStatus}`]: (candidate) =>
      candidate.status === expectedStatus,
  });
  if (!passed) {
    fail(`${operation} failed with HTTP ${response.status}`);
  }
}

function parseJson(response, operation) {
  try {
    return response.json();
  } catch {
    fail(`${operation} returned malformed JSON`);
  }
}

function csrfToken() {
  const response = http.get(
    `${BASE_URL}/api/v1/session/csrf`,
    requestParameters("GET /api/v1/session/csrf", {
      Accept: JSON_MEDIA_TYPE,
    }),
  );
  requireStatus(response, 200, "CSRF bootstrap");
  const payload = parseJson(response, "CSRF bootstrap");

  if (
    typeof payload?.headerName !== "string" ||
    !/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/.test(payload.headerName) ||
    typeof payload?.token !== "string" ||
    payload.token.length === 0 ||
    !/^[\x21-\x7e]+$/.test(payload.token)
  ) {
    fail("CSRF bootstrap returned an unexpected payload");
  }

  return payload;
}

function login(csrf) {
  const response = http.post(
    `${BASE_URL}/api/v1/session`,
    JSON.stringify({ email: EMAIL, password: PASSWORD }),
    requestParameters("POST /api/v1/session", {
      Accept: JSON_MEDIA_TYPE,
      "Content-Type": JSON_MEDIA_TYPE,
      [csrf.headerName]: csrf.token,
    }),
  );
  requireStatus(response, 200, "Login");
}

function telemetryRequest() {
  const maximumObservedAt = Date.now() - 60_000;
  const iterationId = `${execution.vu.idInTest}-${execution.scenario.iterationInTest}`;
  return {
    maximumObservedAt,
    body: {
      idempotencyKey: `k6:${RUN_ID}:${iterationId}`,
      readings: TELEMETRY_VALUES.map((value, index) => ({
        sensorId: SENSOR_ID,
        value,
        observedAt: new Date(
          maximumObservedAt - (5 - index) * 60_000,
        ).toISOString(),
      })),
    },
  };
}

function acceptTelemetry(csrf, request) {
  const startedAt = Date.now();
  const response = http.post(
    `${BASE_URL}/api/v1/telemetry-batches`,
    JSON.stringify(request.body),
    requestParameters("POST /api/v1/telemetry-batches", {
      Accept: JSON_MEDIA_TYPE,
      "Content-Type": JSON_MEDIA_TYPE,
      [csrf.headerName]: csrf.token,
    }),
  );
  telemetryAcceptLatency.add(Date.now() - startedAt);
  requireStatus(response, 200, "Telemetry acceptance");
  const payload = parseJson(response, "Telemetry acceptance");

  if (
    typeof payload?.batchId !== "string" ||
    payload.idempotencyKey !== request.body.idempotencyKey ||
    payload.readingCount !== request.body.readings.length
  ) {
    fail("Telemetry acceptance returned an unexpected payload");
  }
}

function readAlerts(operation) {
  const response = http.get(
    `${BASE_URL}/api/v1/alerts?limit=100`,
    requestParameters("GET /api/v1/alerts", {
      Accept: JSON_MEDIA_TYPE,
    }),
  );
  requireStatus(response, 200, operation);
  const payload = parseJson(response, operation);
  if (!Array.isArray(payload?.alerts)) {
    fail(`${operation} returned an unexpected payload`);
  }
  return payload;
}

function captureAlertBaseline(maximumObservedAt) {
  const payload = readAlerts("Alert baseline");
  const baseline = captureMatchingAlertBaseline(payload, SENSOR_ID, RULE_CODE);
  if (
    baseline === null ||
    !evidenceWindowFollowsBaseline(baseline, maximumObservedAt)
  ) {
    fail("Existing alert state overlaps the planned telemetry evidence window");
  }
  return baseline;
}

function awaitAlert(baseline, maximumObservedAt, telemetryStartedAt) {
  const deadline = Date.now() + ALERT_WAIT_SECONDS * 1000;

  while (Date.now() < deadline) {
    const payload = readAlerts("Alert poll");
    if (
      hasCausalAlertAdvance(
        payload,
        baseline,
        SENSOR_ID,
        RULE_CODE,
        maximumObservedAt,
        EXPECTED_ALERT_OCCURRENCE_DELTA,
      )
    ) {
      telemetryToAlertLatency.add(Date.now() - telemetryStartedAt);
      return;
    }
    sleep(0.5);
  }

  fail(`No matching alert appeared within ${ALERT_WAIT_SECONDS} seconds`);
}

function logout(csrf) {
  const response = http.del(
    `${BASE_URL}/api/v1/session`,
    null,
    requestParameters("DELETE /api/v1/session", {
      Accept: JSON_MEDIA_TYPE,
      [csrf.headerName]: csrf.token,
    }),
  );
  requireStatus(response, 204, "Logout");
}

export default function telemetryToAlertScenario() {
  let activeCsrf;
  let scenarioError;

  try {
    activeCsrf = csrfToken();
    login(activeCsrf);
    activeCsrf = csrfToken();

    const request = telemetryRequest();
    const baseline = captureAlertBaseline(request.maximumObservedAt);
    const telemetryStartedAt = Date.now();
    acceptTelemetry(activeCsrf, request);
    awaitAlert(baseline, request.maximumObservedAt, telemetryStartedAt);
  } catch (error) {
    scenarioError = error;
  }

  if (activeCsrf !== undefined) {
    try {
      logout(activeCsrf);
    } catch (error) {
      scenarioError ??= error;
    }
  }

  if (scenarioError !== undefined) {
    scenarioFailures.add(1);
    scenarioSuccess.add(false);
    throw scenarioError;
  }

  scenarioSuccess.add(true);
}

export function handleSummary(data) {
  const report = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    scenario: "authenticated telemetry acceptance to durable alert visibility",
    targetOrigin: BASE_URL,
    environment: {
      name: ENVIRONMENT_NAME,
      deployRevision: DEPLOY_REVISION,
      protocol: new URL(BASE_URL).protocol,
    },
    boundedConfiguration: {
      vus: VUS,
      iterations: ITERATIONS,
      requestTimeoutSeconds: REQUEST_TIMEOUT_SECONDS,
      alertWaitSeconds: ALERT_WAIT_SECONDS,
      hardMaximums: {
        vus: MAX_VUS,
        iterations: MAX_ITERATIONS,
        requestTimeoutSeconds: MAX_REQUEST_TIMEOUT_SECONDS,
        alertWaitSeconds: MAX_ALERT_WAIT_SECONDS,
      },
    },
    k6SummaryFormat:
      typeof data?.metadata === "object" ? "machine-readable" : "legacy",
    expectedMetricNames: [
      "assetpulse_scenario_success",
      "assetpulse_scenario_failures",
      "assetpulse_telemetry_accept_latency",
      "assetpulse_telemetry_to_alert_latency",
      "http_req_duration",
      "http_req_failed",
      "iterations",
    ],
    limitations: [
      "This bounded check is not a capacity test.",
      "Alert visibility is verified by tenant-safe API polling, not direct database access.",
      "A sleeping free service can add cold-start latency before the measured scenario begins.",
    ],
    rawK6Summary: data,
  };

  return {
    [SUMMARY_PATH]: `${JSON.stringify(report, null, 2)}\n`,
    stdout: `AssetPulse k6 evidence written to ${SUMMARY_PATH}\n`,
  };
}
