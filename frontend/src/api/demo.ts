import type { CsrfToken } from "./session";

export type DemoResetResult = Readonly<{
  resetAt: string;
  alertsResolved: number;
  workOrdersCompleted: number;
}>;

export type AcceptedScenario = Readonly<{
  batchId: string;
  idempotencyKey: string;
  readingCount: number;
  acceptedAt: string;
}>;

export class DemoSessionExpiredError extends Error {
  constructor() {
    super("The demo session has expired");
    this.name = "DemoSessionExpiredError";
  }
}

export class DemoForbiddenError extends Error {
  constructor() {
    super("Demo command access denied");
    this.name = "DemoForbiddenError";
  }
}

export class DemoRequestVerificationError extends Error {
  constructor() {
    super("Demo command request verification failed");
    this.name = "DemoRequestVerificationError";
  }
}

export class DemoResetLimitExceededError extends Error {
  constructor() {
    super("The demo reset safety limit was exceeded");
    this.name = "DemoResetLimitExceededError";
  }
}

export class DemoCommandUncertainError extends Error {
  constructor(options?: ErrorOptions) {
    super("The demo command result is uncertain", options);
    this.name = "DemoCommandUncertainError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
const PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
const NORTHSTAR_SENSOR_ID = "30000000-0000-0000-0000-000000000001";
const OVERHEATING_VALUES = [72, 74, 77.5, 80, 83.5, 86] as const;
const MAX_RESET_RECORDS = 100;
const MINUTE_MS = 60_000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function isExactRecord(
  value: unknown,
  expectedKeys: readonly string[],
): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const actualKeys = Object.keys(value);
  return (
    actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => Object.hasOwn(value, key))
  );
}

function isIsoInstant(value: unknown): value is string {
  return (
    typeof value === "string" &&
    value.trim() === value &&
    !Number.isNaN(Date.parse(value)) &&
    /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
  );
}

function isCount(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function isResetCount(value: unknown): value is number {
  return isCount(value) && Number(value) <= MAX_RESET_RECORDS;
}

async function readProblemCode(response: Response): Promise<string | null> {
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== PROBLEM_JSON_MEDIA_TYPE) {
    return null;
  }
  try {
    const payload: unknown = await response.json();
    return typeof payload === "object" &&
      payload !== null &&
      !Array.isArray(payload) &&
      typeof (payload as Record<string, unknown>).code === "string"
      ? ((payload as Record<string, unknown>).code as string)
      : null;
  } catch {
    return null;
  }
}

async function classifyFailure(response: Response): Promise<void> {
  if (response.status === 401) {
    throw new DemoSessionExpiredError();
  }
  const code = await readProblemCode(response);
  if (response.status === 403) {
    if (code === "CSRF_REJECTED") {
      throw new DemoRequestVerificationError();
    }
    throw new DemoForbiddenError();
  }
  if (response.status === 409 && code === "DEMO_RESET_LIMIT_EXCEEDED") {
    throw new DemoResetLimitExceededError();
  }
  throw new DemoCommandUncertainError();
}

async function readJson(response: Response): Promise<unknown> {
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The demo API returned an unexpected content type");
  }
  return response.json() as Promise<unknown>;
}

function scenarioRequest(now: Date) {
  if (!Number.isFinite(now.getTime())) {
    throw new Error("A valid scenario time is required");
  }
  const anchor = new Date(
    Math.floor((now.getTime() - MINUTE_MS) / MINUTE_MS) * MINUTE_MS,
  );
  const compactInvocation = now
    .toISOString()
    .replaceAll("-", "")
    .replaceAll(":", "")
    .replaceAll(".", "");
  return {
    idempotencyKey: `dashboard:overheating:${compactInvocation}`,
    readings: OVERHEATING_VALUES.map((value, index) => ({
      sensorId: NORTHSTAR_SENSOR_ID,
      value,
      observedAt: new Date(
        anchor.getTime() - (OVERHEATING_VALUES.length - 1 - index) * MINUTE_MS,
      ).toISOString(),
    })),
  };
}

export async function launchOverheatingScenario(
  csrfToken: CsrfToken,
  signal?: AbortSignal,
  now = new Date(),
): Promise<AcceptedScenario> {
  const request = scenarioRequest(now);
  let response: Response;
  try {
    response = await fetch("/api/v1/telemetry-batches", {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      body: JSON.stringify(request),
      signal,
    });
  } catch (error: unknown) {
    throw new DemoCommandUncertainError({ cause: error });
  }
  if (!response.ok) {
    await classifyFailure(response);
  }
  try {
    const payload = await readJson(response);
    if (
      !isExactRecord(payload, [
        "batchId",
        "idempotencyKey",
        "readingCount",
        "acceptedAt",
      ]) ||
      typeof payload.batchId !== "string" ||
      !UUID_PATTERN.test(payload.batchId) ||
      payload.idempotencyKey !== request.idempotencyKey ||
      payload.readingCount !== request.readings.length ||
      !isIsoInstant(payload.acceptedAt)
    ) {
      throw new Error("unexpected scenario response");
    }
    return payload as AcceptedScenario;
  } catch (error: unknown) {
    throw new DemoCommandUncertainError({ cause: error });
  }
}

export async function resetDemo(
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<DemoResetResult> {
  let response: Response;
  try {
    response = await fetch("/api/v1/demo/reset", {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      signal,
    });
  } catch (error: unknown) {
    throw new DemoCommandUncertainError({ cause: error });
  }
  if (!response.ok) {
    await classifyFailure(response);
  }
  try {
    const payload = await readJson(response);
    if (
      !isExactRecord(payload, [
        "resetAt",
        "alertsResolved",
        "workOrdersCompleted",
      ]) ||
      !isIsoInstant(payload.resetAt) ||
      !isResetCount(payload.alertsResolved) ||
      !isResetCount(payload.workOrdersCompleted)
    ) {
      throw new Error("unexpected reset response");
    }
    return payload as DemoResetResult;
  } catch (error: unknown) {
    throw new DemoCommandUncertainError({ cause: error });
  }
}
