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
const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;

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

function parseInstant(value: unknown): bigint | null {
  if (typeof value !== "string" || value.trim() !== value) {
    return null;
  }
  const match = ISO_INSTANT_PATTERN.exec(value);
  if (match === null) {
    return null;
  }
  const [, yearText, monthText, dayText, hourText, minuteText, secondText] =
    match;
  const fraction = match[7] ?? "";
  const offset = match[8];
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const calendarInstant = new Date(0);
  calendarInstant.setUTCFullYear(year, month - 1, day);
  calendarInstant.setUTCHours(hour, minute, second, 0);
  if (
    calendarInstant.getUTCFullYear() !== year ||
    calendarInstant.getUTCMonth() !== month - 1 ||
    calendarInstant.getUTCDate() !== day ||
    calendarInstant.getUTCHours() !== hour ||
    calendarInstant.getUTCMinutes() !== minute ||
    calendarInstant.getUTCSeconds() !== second
  ) {
    return null;
  }
  let offsetMinutes = 0;
  if (offset !== "Z") {
    const offsetHours = Number(offset.slice(1, 3));
    const offsetMinutePart = Number(offset.slice(4, 6));
    if (
      offsetHours > 18 ||
      offsetMinutePart > 59 ||
      (offsetHours === 18 && offsetMinutePart !== 0)
    ) {
      return null;
    }
    offsetMinutes =
      (offsetHours * 60 + offsetMinutePart) * (offset.startsWith("+") ? 1 : -1);
  }
  return (
    BigInt(calendarInstant.getTime() - offsetMinutes * 60 * 1_000) *
      NANOSECONDS_PER_MILLISECOND +
    BigInt(fraction.padEnd(9, "0") || "0")
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
