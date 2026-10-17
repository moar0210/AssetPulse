import { parseInstant } from "./instant";

export const DASHBOARD_ACTIVITY_LIMIT = 5;

export const dashboardActivityActions = [
  "ALERT_ACKNOWLEDGED",
  "ALERT_RESOLVED",
  "WORK_ORDER_CREATED",
  "WORK_ORDER_ASSIGNED",
  "WORK_ORDER_STARTED",
  "WORK_ORDER_COMPLETED",
] as const;

export type DashboardActivityAction = (typeof dashboardActivityActions)[number];
export type DashboardActivitySubjectType = "ALERT" | "WORK_ORDER";

export type DashboardActivity = Readonly<{
  action: DashboardActivityAction;
  subjectType: DashboardActivitySubjectType;
  subjectId: string;
  occurredAt: string;
}>;

export type DashboardSummary = Readonly<{
  assetCount: number;
  openAlertCount: number;
  activeWorkOrderCount: number;
  recentActivity: readonly DashboardActivity[];
}>;

export class DashboardSessionExpiredError extends Error {
  constructor() {
    super("The dashboard session has expired");
    this.name = "DashboardSessionExpiredError";
  }
}

export class DashboardForbiddenError extends Error {
  constructor() {
    super("Dashboard access denied");
    this.name = "DashboardForbiddenError";
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

function isAction(value: unknown): value is DashboardActivityAction {
  return (
    typeof value === "string" &&
    dashboardActivityActions.some((candidate) => candidate === value)
  );
}

function isActivity(value: unknown): value is DashboardActivity {
  if (
    !isExactRecord(value, [
      "action",
      "subjectType",
      "subjectId",
      "occurredAt",
    ]) ||
    !isAction(value.action) ||
    !["ALERT", "WORK_ORDER"].includes(String(value.subjectType)) ||
    typeof value.subjectId !== "string" ||
    !UUID_PATTERN.test(value.subjectId) ||
    parseInstant(value.occurredAt) === null
  ) {
    return false;
  }
  return (
    (value.action.startsWith("ALERT_") && value.subjectType === "ALERT") ||
    (value.action.startsWith("WORK_ORDER_") &&
      value.subjectType === "WORK_ORDER")
  );
}

function isCount(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) >= 0;
}

function parseDashboard(value: unknown): DashboardSummary {
  if (
    !isExactRecord(value, [
      "assetCount",
      "openAlertCount",
      "activeWorkOrderCount",
      "recentActivity",
    ]) ||
    !isCount(value.assetCount) ||
    !isCount(value.openAlertCount) ||
    !isCount(value.activeWorkOrderCount) ||
    !Array.isArray(value.recentActivity) ||
    value.recentActivity.length > DASHBOARD_ACTIVITY_LIMIT ||
    !value.recentActivity.every(isActivity)
  ) {
    throw new Error("The dashboard API returned an unexpected payload");
  }

  let previousInstant: bigint | null = null;
  for (const activity of value.recentActivity) {
    const occurredAt = parseInstant(activity.occurredAt)!;
    if (previousInstant !== null && previousInstant < occurredAt) {
      throw new Error("The dashboard API returned an unexpected payload");
    }
    previousInstant = occurredAt;
  }
  return value as DashboardSummary;
}

export async function getDashboard(
  signal?: AbortSignal,
): Promise<DashboardSummary> {
  const response = await fetch("/api/v1/dashboard", {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    cache: "no-store",
    signal,
  });
  if (response.status === 401) {
    throw new DashboardSessionExpiredError();
  }
  if (response.status === 403) {
    throw new DashboardForbiddenError();
  }
  if (!response.ok) {
    throw new Error("The dashboard API returned an unsuccessful response");
  }
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The dashboard API returned an unexpected content type");
  }
  return parseDashboard(await response.json());
}
