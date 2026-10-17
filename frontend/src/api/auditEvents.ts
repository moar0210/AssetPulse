import { parseInstant } from "./instant";

export const DEFAULT_AUDIT_EVENT_LIMIT = 50;
export const MAX_AUDIT_EVENT_LIMIT = 100;

const subjectTypeByAction = {
  AUTHENTICATION_SUCCEEDED: "USER",
  SESSION_ENDED: "USER",
  DEMO_RESET: "USER",
  ALERT_ACKNOWLEDGED: "ALERT",
  ALERT_RESOLVED: "ALERT",
  WORK_ORDER_CREATED: "WORK_ORDER",
  WORK_ORDER_ASSIGNED: "WORK_ORDER",
  WORK_ORDER_STARTED: "WORK_ORDER",
  WORK_ORDER_COMPLETED: "WORK_ORDER",
  PROCESSING_EVENT_RETRIED: "PROCESSING_EVENT",
} as const;

export type AuditAction = keyof typeof subjectTypeByAction;
export type AuditSubjectType = (typeof subjectTypeByAction)[AuditAction];

export type AuditEvent = Readonly<{
  id: string;
  actor: Readonly<{ id: string; displayName: string }>;
  action: AuditAction;
  subject: Readonly<{ type: AuditSubjectType; id: string }>;
  occurredAt: string;
  correlationId: string;
}>;

export type AuditEventList = Readonly<{
  events: readonly AuditEvent[];
  limit: number;
}>;

export class AuditSessionExpiredError extends Error {
  constructor() {
    super("The audit session has expired");
    this.name = "AuditSessionExpiredError";
  }
}

export class AuditForbiddenError extends Error {
  constructor() {
    super("Audit access denied");
    this.name = "AuditForbiddenError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
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

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isAuditAction(value: unknown): value is AuditAction {
  return typeof value === "string" && Object.hasOwn(subjectTypeByAction, value);
}

function isAuditEvent(value: unknown): value is AuditEvent {
  if (
    !isExactRecord(value, [
      "id",
      "actor",
      "action",
      "subject",
      "occurredAt",
      "correlationId",
    ]) ||
    !isUuid(value.id) ||
    !isUuid(value.correlationId) ||
    !isExactRecord(value.actor, ["id", "displayName"]) ||
    !isUuid(value.actor.id) ||
    typeof value.actor.displayName !== "string" ||
    value.actor.displayName.length < 1 ||
    value.actor.displayName.length > 120 ||
    value.actor.displayName.trim() !== value.actor.displayName ||
    !isAuditAction(value.action) ||
    !isExactRecord(value.subject, ["type", "id"]) ||
    !isUuid(value.subject.id) ||
    value.subject.type !== subjectTypeByAction[value.action] ||
    parseInstant(value.occurredAt) === null
  ) {
    return false;
  }

  return (
    value.subject.type !== "USER" ||
    value.actor.id.toLowerCase() === value.subject.id.toLowerCase()
  );
}

function parseAuditEventList(
  value: unknown,
  expectedLimit: number,
): AuditEventList {
  if (
    !isExactRecord(value, ["events", "limit"]) ||
    value.limit !== expectedLimit ||
    !Array.isArray(value.events) ||
    value.events.length > expectedLimit ||
    !value.events.every(isAuditEvent)
  ) {
    throw new Error("The audit-events endpoint returned an unexpected payload");
  }

  const events = value.events as AuditEvent[];
  const seenIds = new Set<string>();
  for (let index = 0; index < events.length; index += 1) {
    const event = events[index];
    const normalizedId = event.id.toLowerCase();
    if (seenIds.has(normalizedId)) {
      throw new Error(
        "The audit-events endpoint returned an unexpected payload",
      );
    }
    seenIds.add(normalizedId);

    const previous = events[index - 1];
    if (previous !== undefined) {
      const previousOccurredAt = parseInstant(previous.occurredAt)!;
      const currentOccurredAt = parseInstant(event.occurredAt)!;
      if (
        previousOccurredAt < currentOccurredAt ||
        (previousOccurredAt === currentOccurredAt &&
          previous.id.toLowerCase() < normalizedId)
      ) {
        throw new Error(
          "The audit-events endpoint returned an unexpected payload",
        );
      }
    }
  }

  return { events, limit: expectedLimit };
}

export async function getAuditEvents(
  limit = DEFAULT_AUDIT_EVENT_LIMIT,
  signal?: AbortSignal,
): Promise<AuditEventList> {
  if (
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > MAX_AUDIT_EVENT_LIMIT
  ) {
    throw new Error("An audit-event limit from 1 through 100 is required");
  }

  const response = await fetch(`/api/v1/audit-events?limit=${limit}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    cache: "no-store",
    signal,
  });
  if (response.status === 401) {
    throw new AuditSessionExpiredError();
  }
  if (response.status === 403) {
    throw new AuditForbiddenError();
  }
  if (!response.ok) {
    throw new Error("The audit-events API returned an unsuccessful response");
  }

  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The audit-events API returned an unexpected content type");
  }
  const payload: unknown = await response.json();
  return parseAuditEventList(payload, limit);
}
