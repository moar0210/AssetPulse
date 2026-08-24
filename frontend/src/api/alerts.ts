import type { CsrfToken } from "./session";

export const DEFAULT_ALERT_LIMIT = 50;
export const MAX_ALERT_LIMIT = 100;

export const alertStatuses = ["OPEN", "ACKNOWLEDGED", "RESOLVED"] as const;
export type AlertStatus = (typeof alertStatuses)[number];

export const alertChangeTypes = [
  "OCCURRENCE_RECORDED",
  "STATUS_CHANGED",
] as const;
export type AlertChangeType = (typeof alertChangeTypes)[number];

export type AlertContext = Readonly<{
  asset: Readonly<{
    id: string;
    assetCode: string;
    name: string;
  }>;
  sensor: Readonly<{
    id: string;
    sensorKey: string;
    name: string;
    measurementType: "TEMPERATURE";
    unit: "CELSIUS";
  }>;
  thresholdRule: Readonly<{
    id: string;
    ruleCode: string;
    name: string;
    comparison: "GREATER_THAN_OR_EQUAL_TO";
    thresholdValue: number;
    cooldownSeconds: number;
  }>;
}>;

export type AlertSummary = Readonly<{
  id: string;
  status: AlertStatus;
  occurrenceCount: number;
  lastOccurredAt: string;
  context: AlertContext;
}>;

export type AlertList = Readonly<{
  alerts: readonly AlertSummary[];
  limit: number;
}>;

export type AlertHistory = Readonly<{
  sequenceNumber: number;
  fromStatus: AlertStatus;
  toStatus: AlertStatus;
  actor: Readonly<{
    id: string;
    displayName: string;
  }>;
  transitionedAt: string;
}>;

export type AlertDetail = Readonly<{
  id: string;
  status: AlertStatus;
  occurrenceCount: number;
  firstOccurredAt: string;
  lastOccurredAt: string;
  cooldownUntil: string;
  createdAt: string;
  updatedAt: string;
  context: AlertContext;
  history: readonly AlertHistory[];
}>;

export type AlertChange = Readonly<{
  alertId: string;
  changeType: AlertChangeType;
}>;

export class AlertSessionExpiredError extends Error {
  constructor() {
    super("The alert session has expired");
    this.name = "AlertSessionExpiredError";
  }
}

export class AlertNotFoundError extends Error {
  constructor() {
    super("Alert not found");
    this.name = "AlertNotFoundError";
  }
}

export class AlertForbiddenError extends Error {
  constructor() {
    super("Alert access denied");
    this.name = "AlertForbiddenError";
  }
}

export class AlertRequestVerificationError extends Error {
  constructor() {
    super("Alert request verification failed");
    this.name = "AlertRequestVerificationError";
  }
}

export class AlertStateConflictError extends Error {
  constructor() {
    super("Alert state conflict");
    this.name = "AlertStateConflictError";
  }
}

export class AlertCommandUncertainError extends Error {
  constructor(options?: ErrorOptions) {
    super("The alert command result could not be confirmed", options);
    this.name = "AlertCommandUncertainError";
  }
}

export type AlertEventSourceFactory = (
  url: string,
  eventSourceInit: EventSourceInit,
) => EventSource;

export type AlertStreamCallbacks = Readonly<{
  onOpen: () => void;
  onChange: (change: AlertChange) => void;
  onError: () => void;
  onProtocolError: () => void;
}>;

const JSON_MEDIA_TYPE = "application/json";
const PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONFIGURATION_KEY_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/;
const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;
const MAX_THRESHOLD_VALUE = 1_000_000_000_000;
const MAX_COOLDOWN_SECONDS = 604_800;
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

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isBoundedTrimmedText(
  value: unknown,
  maximumLength: number,
): value is string {
  return (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= maximumLength &&
    value.trim() === value
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

function isAlertStatus(value: unknown): value is AlertStatus {
  return (
    typeof value === "string" &&
    alertStatuses.some((status) => status === value)
  );
}

function isAlertChangeType(value: unknown): value is AlertChangeType {
  return (
    typeof value === "string" &&
    alertChangeTypes.some((changeType) => changeType === value)
  );
}

function isAlertContext(value: unknown): value is AlertContext {
  if (!isExactRecord(value, ["asset", "sensor", "thresholdRule"])) {
    return false;
  }

  const { asset, sensor, thresholdRule } = value;
  return (
    isExactRecord(asset, ["id", "assetCode", "name"]) &&
    isUuid(asset.id) &&
    isBoundedTrimmedText(asset.assetCode, 64) &&
    isBoundedTrimmedText(asset.name, 120) &&
    isExactRecord(sensor, [
      "id",
      "sensorKey",
      "name",
      "measurementType",
      "unit",
    ]) &&
    isUuid(sensor.id) &&
    isBoundedTrimmedText(sensor.sensorKey, 64) &&
    CONFIGURATION_KEY_PATTERN.test(sensor.sensorKey) &&
    isBoundedTrimmedText(sensor.name, 120) &&
    sensor.measurementType === "TEMPERATURE" &&
    sensor.unit === "CELSIUS" &&
    isExactRecord(thresholdRule, [
      "id",
      "ruleCode",
      "name",
      "comparison",
      "thresholdValue",
      "cooldownSeconds",
    ]) &&
    isUuid(thresholdRule.id) &&
    isBoundedTrimmedText(thresholdRule.ruleCode, 64) &&
    CONFIGURATION_KEY_PATTERN.test(thresholdRule.ruleCode) &&
    isBoundedTrimmedText(thresholdRule.name, 120) &&
    thresholdRule.comparison === "GREATER_THAN_OR_EQUAL_TO" &&
    typeof thresholdRule.thresholdValue === "number" &&
    Number.isFinite(thresholdRule.thresholdValue) &&
    Math.abs(thresholdRule.thresholdValue) <= MAX_THRESHOLD_VALUE &&
    typeof thresholdRule.cooldownSeconds === "number" &&
    Number.isSafeInteger(thresholdRule.cooldownSeconds) &&
    thresholdRule.cooldownSeconds >= 0 &&
    thresholdRule.cooldownSeconds <= MAX_COOLDOWN_SECONDS
  );
}

function isAlertSummary(value: unknown): value is AlertSummary {
  return (
    isExactRecord(value, [
      "id",
      "status",
      "occurrenceCount",
      "lastOccurredAt",
      "context",
    ]) &&
    isUuid(value.id) &&
    isAlertStatus(value.status) &&
    typeof value.occurrenceCount === "number" &&
    Number.isSafeInteger(value.occurrenceCount) &&
    value.occurrenceCount >= 1 &&
    parseInstant(value.lastOccurredAt) !== null &&
    isAlertContext(value.context)
  );
}

function isAlertHistory(value: unknown, index: number): value is AlertHistory {
  if (
    !isExactRecord(value, [
      "sequenceNumber",
      "fromStatus",
      "toStatus",
      "actor",
      "transitionedAt",
    ]) ||
    value.sequenceNumber !== index + 1 ||
    !isAlertStatus(value.fromStatus) ||
    !isAlertStatus(value.toStatus) ||
    !isExactRecord(value.actor, ["id", "displayName"]) ||
    !isUuid(value.actor.id) ||
    !isBoundedTrimmedText(value.actor.displayName, 120) ||
    parseInstant(value.transitionedAt) === null
  ) {
    return false;
  }

  return index === 0
    ? value.fromStatus === "OPEN" && value.toStatus === "ACKNOWLEDGED"
    : index === 1 &&
        value.fromStatus === "ACKNOWLEDGED" &&
        value.toStatus === "RESOLVED";
}

function hasValidHistory(
  history: readonly unknown[],
  status: AlertStatus,
): history is readonly AlertHistory[] {
  const expectedLength =
    status === "OPEN" ? 0 : status === "ACKNOWLEDGED" ? 1 : 2;
  if (
    history.length !== expectedLength ||
    !history.every((entry, index) => isAlertHistory(entry, index))
  ) {
    return false;
  }

  for (let index = 1; index < history.length; index += 1) {
    const previous = history[index - 1];
    const current = history[index];
    if (
      !isAlertHistory(previous, index - 1) ||
      !isAlertHistory(current, index) ||
      parseInstant(previous.transitionedAt)! >
        parseInstant(current.transitionedAt)!
    ) {
      return false;
    }
  }
  return true;
}

function parseAlertList(value: unknown, expectedLimit: number): AlertList {
  if (
    !isExactRecord(value, ["alerts", "limit"]) ||
    value.limit !== expectedLimit ||
    !Array.isArray(value.alerts) ||
    value.alerts.length > expectedLimit ||
    !value.alerts.every(isAlertSummary)
  ) {
    throw new Error("The alerts endpoint returned an unexpected payload");
  }

  const seenIds = new Set<string>();
  for (let index = 0; index < value.alerts.length; index += 1) {
    const alert = value.alerts[index];
    if (!isAlertSummary(alert)) {
      throw new Error("The alerts endpoint returned an unexpected payload");
    }
    const normalizedId = alert.id.toLowerCase();
    if (seenIds.has(normalizedId)) {
      throw new Error("The alerts endpoint returned an unexpected payload");
    }
    seenIds.add(normalizedId);

    const previous = value.alerts[index - 1];
    if (previous !== undefined && isAlertSummary(previous)) {
      const previousInstant = parseInstant(previous.lastOccurredAt)!;
      const currentInstant = parseInstant(alert.lastOccurredAt)!;
      if (
        previousInstant < currentInstant ||
        (previousInstant === currentInstant &&
          previous.id.toLowerCase() > alert.id.toLowerCase())
      ) {
        throw new Error("The alerts endpoint returned an unexpected payload");
      }
    }
  }

  return value as AlertList;
}

function parseAlertDetail(
  value: unknown,
  expectedAlertId: string,
): AlertDetail {
  if (
    !isExactRecord(value, [
      "id",
      "status",
      "occurrenceCount",
      "firstOccurredAt",
      "lastOccurredAt",
      "cooldownUntil",
      "createdAt",
      "updatedAt",
      "context",
      "history",
    ]) ||
    !isUuid(value.id) ||
    value.id.toLowerCase() !== expectedAlertId.toLowerCase() ||
    !isAlertStatus(value.status) ||
    typeof value.occurrenceCount !== "number" ||
    !Number.isSafeInteger(value.occurrenceCount) ||
    value.occurrenceCount < 1 ||
    !isAlertContext(value.context) ||
    !Array.isArray(value.history) ||
    !hasValidHistory(value.history, value.status)
  ) {
    throw new Error("The alert detail endpoint returned an unexpected payload");
  }

  const firstOccurredAt = parseInstant(value.firstOccurredAt);
  const lastOccurredAt = parseInstant(value.lastOccurredAt);
  const cooldownUntil = parseInstant(value.cooldownUntil);
  const createdAt = parseInstant(value.createdAt);
  const updatedAt = parseInstant(value.updatedAt);
  if (
    firstOccurredAt === null ||
    lastOccurredAt === null ||
    cooldownUntil === null ||
    createdAt === null ||
    updatedAt === null ||
    firstOccurredAt > lastOccurredAt ||
    cooldownUntil < lastOccurredAt ||
    createdAt > updatedAt
  ) {
    throw new Error("The alert detail endpoint returned an unexpected payload");
  }

  return value as AlertDetail;
}

function parseAlertChange(value: unknown): AlertChange {
  if (
    !isExactRecord(value, ["alertId", "changeType"]) ||
    !isUuid(value.alertId) ||
    !isAlertChangeType(value.changeType)
  ) {
    throw new Error("The alert stream returned an unexpected event");
  }
  return value as AlertChange;
}

async function readJson(response: Response): Promise<unknown> {
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The alerts API returned an unexpected content type");
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

function requireAlertId(alertId: string): void {
  if (!UUID_PATTERN.test(alertId)) {
    throw new Error("A valid alert identifier is required");
  }
}

function classifyReadFailure(response: Response): void {
  if (response.status === 401) {
    throw new AlertSessionExpiredError();
  }
  if (response.status === 403) {
    throw new AlertForbiddenError();
  }
  if (response.status === 404) {
    throw new AlertNotFoundError();
  }
  if (!response.ok) {
    throw new Error("The alerts API returned an unsuccessful response");
  }
}

export async function getAlerts(
  limit = DEFAULT_ALERT_LIMIT,
  signal?: AbortSignal,
): Promise<AlertList> {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_ALERT_LIMIT) {
    throw new Error("An alert limit from 1 through 100 is required");
  }

  const response = await fetch(`/api/v1/alerts?limit=${limit}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });
  classifyReadFailure(response);
  return parseAlertList(await readJson(response), limit);
}

export async function getAlertDetail(
  alertId: string,
  signal?: AbortSignal,
): Promise<AlertDetail> {
  requireAlertId(alertId);
  const response = await fetch(`/api/v1/alerts/${alertId}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });
  classifyReadFailure(response);
  return parseAlertDetail(await readJson(response), alertId);
}

async function commandAlert(
  alertId: string,
  command: "acknowledge" | "resolve",
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<AlertDetail> {
  requireAlertId(alertId);
  let response: Response;
  try {
    response = await fetch(`/api/v1/alerts/${alertId}/${command}`, {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      signal,
    });
  } catch (error: unknown) {
    throw new AlertCommandUncertainError({ cause: error });
  }

  if (response.status === 401) {
    throw new AlertSessionExpiredError();
  }
  if (response.status === 403) {
    const problemCode = await readProblemCode(response);
    if (problemCode === "CSRF_REJECTED") {
      throw new AlertRequestVerificationError();
    }
    throw new AlertForbiddenError();
  }
  if (response.status === 404) {
    throw new AlertNotFoundError();
  }
  if (response.status === 409) {
    throw new AlertStateConflictError();
  }
  if (!response.ok) {
    throw new AlertCommandUncertainError();
  }

  try {
    const detail = parseAlertDetail(await readJson(response), alertId);
    const expectedStatus =
      command === "acknowledge" ? "ACKNOWLEDGED" : "RESOLVED";
    if (detail.status !== expectedStatus) {
      throw new Error("unexpected alert command target status");
    }
    return detail;
  } catch (error: unknown) {
    throw new AlertCommandUncertainError({ cause: error });
  }
}

export function acknowledgeAlert(
  alertId: string,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<AlertDetail> {
  return commandAlert(alertId, "acknowledge", csrfToken, signal);
}

export function resolveAlert(
  alertId: string,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<AlertDetail> {
  return commandAlert(alertId, "resolve", csrfToken, signal);
}

export function subscribeToAlertChanges(
  callbacks: AlertStreamCallbacks,
  createEventSource: AlertEventSourceFactory = (url, init) =>
    new EventSource(url, init),
): () => void {
  const eventSource = createEventSource("/api/v1/alerts/stream", {
    withCredentials: true,
  });

  eventSource.addEventListener("open", callbacks.onOpen);
  eventSource.addEventListener("error", callbacks.onError);
  eventSource.addEventListener("alert-changed", (event) => {
    let change: AlertChange;
    try {
      const message = event as MessageEvent<string>;
      change = parseAlertChange(JSON.parse(message.data));
    } catch {
      callbacks.onProtocolError();
      return;
    }
    callbacks.onChange(change);
  });

  return () => eventSource.close();
}
