import { parseInstant } from "./instant";

import type { CsrfToken } from "./session";

export const DEFAULT_DEAD_PROCESSING_EVENT_LIMIT = 50;
export const MAX_DEAD_PROCESSING_EVENT_LIMIT = 100;

export const processingFailureDetails = {
  PROCESSING_FAILED: "Processing failed; another attempt may be scheduled.",
  LEASE_EXPIRED: "Processing lease expired after the final attempt.",
} as const;

export type ProcessingFailureCode = keyof typeof processingFailureDetails;

type ProcessingFailure = {
  [Code in ProcessingFailureCode]: Readonly<{
    lastErrorCode: Code;
    lastErrorMessage: (typeof processingFailureDetails)[Code];
  }>;
}[ProcessingFailureCode];

export type DeadProcessingEvent = Readonly<{
  id: string;
  telemetryBatchId: string;
  eventType: "TELEMETRY_BATCH_ACCEPTED";
  attemptCount: 5;
  createdAt: string;
  deadAt: string;
  updatedAt: string;
}> &
  ProcessingFailure;

export type DeadProcessingEventList = Readonly<{
  events: readonly DeadProcessingEvent[];
  limit: number;
}>;

export class ProcessingEventSessionExpiredError extends Error {
  constructor() {
    super("The processing-event session has expired");
    this.name = "ProcessingEventSessionExpiredError";
  }
}

export class ProcessingEventForbiddenError extends Error {
  constructor() {
    super("Processing-event access denied");
    this.name = "ProcessingEventForbiddenError";
  }
}

export class ProcessingEventRequestVerificationError extends Error {
  constructor() {
    super("Processing-event request verification failed");
    this.name = "ProcessingEventRequestVerificationError";
  }
}

export class ProcessingEventNotFoundError extends Error {
  constructor() {
    super("Processing event not found");
    this.name = "ProcessingEventNotFoundError";
  }
}

export class ProcessingEventStateConflictError extends Error {
  constructor() {
    super("Processing-event state conflict");
    this.name = "ProcessingEventStateConflictError";
  }
}

export class ProcessingEventRetryUncertainError extends Error {
  constructor(options?: ErrorOptions) {
    super("The processing-event retry result could not be confirmed", options);
    this.name = "ProcessingEventRetryUncertainError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
const PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
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

function isSafeProcessingFailure(
  value: Record<string, unknown>,
): value is Record<string, unknown> & ProcessingFailure {
  return (
    (value.lastErrorCode === "PROCESSING_FAILED" &&
      value.lastErrorMessage === processingFailureDetails.PROCESSING_FAILED) ||
    (value.lastErrorCode === "LEASE_EXPIRED" &&
      value.lastErrorMessage === processingFailureDetails.LEASE_EXPIRED)
  );
}

function isDeadProcessingEvent(value: unknown): value is DeadProcessingEvent {
  if (
    !isExactRecord(value, [
      "id",
      "telemetryBatchId",
      "eventType",
      "attemptCount",
      "createdAt",
      "deadAt",
      "updatedAt",
      "lastErrorCode",
      "lastErrorMessage",
    ]) ||
    typeof value.id !== "string" ||
    !UUID_PATTERN.test(value.id) ||
    typeof value.telemetryBatchId !== "string" ||
    !UUID_PATTERN.test(value.telemetryBatchId) ||
    value.eventType !== "TELEMETRY_BATCH_ACCEPTED" ||
    value.attemptCount !== 5 ||
    !isSafeProcessingFailure(value)
  ) {
    return false;
  }

  const createdAt = parseInstant(value.createdAt);
  const deadAt = parseInstant(value.deadAt);
  const updatedAt = parseInstant(value.updatedAt);
  return (
    createdAt !== null &&
    deadAt !== null &&
    updatedAt !== null &&
    createdAt <= deadAt &&
    deadAt <= updatedAt
  );
}

function parseDeadProcessingEventList(
  value: unknown,
  expectedLimit: number,
): DeadProcessingEventList {
  if (
    !isExactRecord(value, ["events", "limit"]) ||
    value.limit !== expectedLimit ||
    !Array.isArray(value.events) ||
    value.events.length > expectedLimit ||
    !value.events.every(isDeadProcessingEvent)
  ) {
    throw new Error(
      "The dead processing-events endpoint returned an unexpected payload",
    );
  }

  const seenIds = new Set<string>();
  for (let index = 0; index < value.events.length; index += 1) {
    const event = value.events[index];
    if (!isDeadProcessingEvent(event)) {
      throw new Error(
        "The dead processing-events endpoint returned an unexpected payload",
      );
    }

    const normalizedId = event.id.toLowerCase();
    if (seenIds.has(normalizedId)) {
      throw new Error(
        "The dead processing-events endpoint returned an unexpected payload",
      );
    }
    seenIds.add(normalizedId);

    const previous = value.events[index - 1];
    if (previous !== undefined && isDeadProcessingEvent(previous)) {
      const previousDeadAt = parseInstant(previous.deadAt)!;
      const currentDeadAt = parseInstant(event.deadAt)!;
      if (
        previousDeadAt < currentDeadAt ||
        (previousDeadAt === currentDeadAt &&
          previous.id.toLowerCase() > normalizedId)
      ) {
        throw new Error(
          "The dead processing-events endpoint returned an unexpected payload",
        );
      }
    }
  }

  return value as DeadProcessingEventList;
}

async function readJson(response: Response): Promise<unknown> {
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error(
      "The processing-events API returned an unexpected content type",
    );
  }
  return response.json() as Promise<unknown>;
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

function requireEventId(eventId: string): void {
  if (!UUID_PATTERN.test(eventId)) {
    throw new Error("A valid processing-event identifier is required");
  }
}

function classifyReadFailure(response: Response): void {
  if (response.status === 401) {
    throw new ProcessingEventSessionExpiredError();
  }
  if (response.status === 403) {
    throw new ProcessingEventForbiddenError();
  }
  if (!response.ok) {
    throw new Error(
      "The processing-events API returned an unsuccessful response",
    );
  }
}

export async function getDeadProcessingEvents(
  limit = DEFAULT_DEAD_PROCESSING_EVENT_LIMIT,
  signal?: AbortSignal,
): Promise<DeadProcessingEventList> {
  if (
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > MAX_DEAD_PROCESSING_EVENT_LIMIT
  ) {
    throw new Error("A processing-event limit from 1 through 100 is required");
  }

  const response = await fetch(
    `/api/v1/processing-events/dead?limit=${limit}`,
    {
      method: "GET",
      headers: { Accept: JSON_MEDIA_TYPE },
      credentials: "same-origin",
      signal,
    },
  );
  classifyReadFailure(response);
  return parseDeadProcessingEventList(await readJson(response), limit);
}

export async function retryDeadProcessingEvent(
  eventId: string,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<void> {
  requireEventId(eventId);

  let response: Response;
  try {
    response = await fetch(`/api/v1/processing-events/${eventId}/retry`, {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      signal,
    });
  } catch (error: unknown) {
    throw new ProcessingEventRetryUncertainError({ cause: error });
  }

  if (response.status === 401) {
    throw new ProcessingEventSessionExpiredError();
  }
  if (response.status === 403) {
    const problemCode = await readProblemCode(response);
    if (problemCode === "CSRF_REJECTED") {
      throw new ProcessingEventRequestVerificationError();
    }
    throw new ProcessingEventForbiddenError();
  }
  if (response.status === 404) {
    throw new ProcessingEventNotFoundError();
  }
  if (response.status === 409) {
    throw new ProcessingEventStateConflictError();
  }
  if (response.status !== 204) {
    throw new ProcessingEventRetryUncertainError();
  }
}
