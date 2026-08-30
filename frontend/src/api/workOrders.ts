import type { CsrfToken } from "./session";

export const DEFAULT_WORK_ORDER_LIMIT = 50;
export const MAX_WORK_ORDER_LIMIT = 100;

export const workOrderStatuses = [
  "OPEN",
  "ASSIGNED",
  "IN_PROGRESS",
  "DONE",
] as const;
export type WorkOrderStatus = (typeof workOrderStatuses)[number];

export type WorkOrderTechnician = Readonly<{
  id: string;
  displayName: string;
}>;

export type WorkOrder = Readonly<{
  id: string;
  alertId: string;
  status: WorkOrderStatus;
  version: number;
  assignedTechnician: WorkOrderTechnician | null;
  createdAt: string;
  updatedAt: string;
  context: Readonly<{
    assetId: string;
    assetCode: string;
    assetName: string;
    ruleName: string;
  }>;
}>;

export type WorkOrderHistory = Readonly<{
  sequenceNumber: number;
  fromStatus: WorkOrderStatus;
  toStatus: WorkOrderStatus;
  actor: Readonly<{ id: string; displayName: string }> | null;
  transitionedAt: string;
}>;

export type WorkOrderDetail = WorkOrder &
  Readonly<{ history: readonly WorkOrderHistory[] }>;

export type WorkOrderList = Readonly<{
  workOrders: readonly WorkOrder[];
  limit: number;
}>;

export type EligibleTechnicianList = Readonly<{
  technicians: readonly WorkOrderTechnician[];
}>;

export class WorkOrderSessionExpiredError extends Error {
  constructor() {
    super("The work-order session has expired");
    this.name = "WorkOrderSessionExpiredError";
  }
}

export class WorkOrderForbiddenError extends Error {
  constructor() {
    super("Work-order access denied");
    this.name = "WorkOrderForbiddenError";
  }
}

export class WorkOrderRequestVerificationError extends Error {
  constructor() {
    super("Work-order request verification failed");
    this.name = "WorkOrderRequestVerificationError";
  }
}

export class WorkOrderSourceAlertNotFoundError extends Error {
  constructor() {
    super("The source alert was not found");
    this.name = "WorkOrderSourceAlertNotFoundError";
  }
}

export class WorkOrderNotFoundError extends Error {
  constructor() {
    super("Work order not found");
    this.name = "WorkOrderNotFoundError";
  }
}

export class WorkOrderAlreadyExistsError extends Error {
  constructor() {
    super("A work order already exists for this alert");
    this.name = "WorkOrderAlreadyExistsError";
  }
}

export class WorkOrderStateConflictError extends Error {
  constructor() {
    super("Work-order state conflict");
    this.name = "WorkOrderStateConflictError";
  }
}

export class InvalidWorkOrderAssigneeError extends Error {
  constructor() {
    super("The selected work-order assignee is invalid");
    this.name = "InvalidWorkOrderAssigneeError";
  }
}

// Both names are exported so callers can phrase the domain concept naturally.
export { InvalidWorkOrderAssigneeError as WorkOrderInvalidAssigneeError };

export class WorkOrderCommandUncertainError extends Error {
  constructor(options?: ErrorOptions) {
    super("The work-order command result could not be confirmed", options);
    this.name = "WorkOrderCommandUncertainError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
const PROBLEM_JSON_MEDIA_TYPE = "application/problem+json";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONFIGURATION_KEY_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/;
const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;
const WORK_ORDER_KEYS = [
  "id",
  "alertId",
  "status",
  "version",
  "assignedTechnician",
  "createdAt",
  "updatedAt",
  "context",
] as const;

function isExactRecord(
  value: unknown,
  expectedKeys: readonly string[],
): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const keys = Object.keys(value);
  return (
    keys.length === expectedKeys.length &&
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
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const fraction = match[7] ?? "";
  const offset = match[8]!;
  const instant = new Date(0);
  instant.setUTCFullYear(year, month - 1, day);
  instant.setUTCHours(hour, minute, second, 0);
  if (
    instant.getUTCFullYear() !== year ||
    instant.getUTCMonth() !== month - 1 ||
    instant.getUTCDate() !== day ||
    instant.getUTCHours() !== hour ||
    instant.getUTCMinutes() !== minute ||
    instant.getUTCSeconds() !== second
  ) {
    return null;
  }
  let offsetMinutes = 0;
  if (offset !== "Z") {
    const hours = Number(offset.slice(1, 3));
    const minutes = Number(offset.slice(4, 6));
    if (hours > 18 || minutes > 59 || (hours === 18 && minutes !== 0)) {
      return null;
    }
    offsetMinutes = (hours * 60 + minutes) * (offset.startsWith("+") ? 1 : -1);
  }
  return (
    BigInt(instant.getTime() - offsetMinutes * 60_000) *
      NANOSECONDS_PER_MILLISECOND +
    BigInt(fraction.padEnd(9, "0") || "0")
  );
}

function isUserReference(value: unknown): value is WorkOrderTechnician {
  return (
    isExactRecord(value, ["id", "displayName"]) &&
    isUuid(value.id) &&
    isBoundedTrimmedText(value.displayName, 120)
  );
}

function parseWorkOrder(value: unknown, expectedId?: string): WorkOrder {
  if (
    !isExactRecord(value, WORK_ORDER_KEYS) ||
    !isUuid(value.id) ||
    (expectedId !== undefined &&
      value.id.toLowerCase() !== expectedId.toLowerCase()) ||
    !isUuid(value.alertId) ||
    !workOrderStatuses.some((status) => status === value.status) ||
    typeof value.version !== "number" ||
    !Number.isSafeInteger(value.version) ||
    workOrderStatuses[value.version] !== value.status ||
    (value.status === "OPEN" && value.assignedTechnician !== null) ||
    (value.status !== "OPEN" && !isUserReference(value.assignedTechnician)) ||
    !isExactRecord(value.context, [
      "assetId",
      "assetCode",
      "assetName",
      "ruleName",
    ]) ||
    !isUuid(value.context.assetId) ||
    !isBoundedTrimmedText(value.context.assetCode, 64) ||
    !CONFIGURATION_KEY_PATTERN.test(value.context.assetCode) ||
    !isBoundedTrimmedText(value.context.assetName, 120) ||
    !isBoundedTrimmedText(value.context.ruleName, 120)
  ) {
    throw new Error("The work-orders API returned an unexpected payload");
  }
  const createdAt = parseInstant(value.createdAt);
  const updatedAt = parseInstant(value.updatedAt);
  if (createdAt === null || updatedAt === null || createdAt > updatedAt) {
    throw new Error("The work-orders API returned an unexpected payload");
  }
  return value as WorkOrder;
}

function parseWorkOrderDetail(
  value: unknown,
  expectedId?: string,
): WorkOrderDetail {
  if (!isExactRecord(value, [...WORK_ORDER_KEYS, "history"])) {
    throw new Error("The work-orders API returned an unexpected payload");
  }
  const { history, ...summary } = value;
  const workOrder = parseWorkOrder(summary, expectedId);
  if (!Array.isArray(history) || history.length !== workOrder.version) {
    throw new Error("The work-orders API returned an unexpected payload");
  }
  let previousAt = parseInstant(workOrder.createdAt)!;
  const updatedAt = parseInstant(workOrder.updatedAt)!;
  for (const [index, entry] of history.entries()) {
    if (
      !isExactRecord(entry, [
        "sequenceNumber",
        "fromStatus",
        "toStatus",
        "actor",
        "transitionedAt",
      ]) ||
      entry.sequenceNumber !== index + 1 ||
      entry.fromStatus !== workOrderStatuses[index] ||
      entry.toStatus !== workOrderStatuses[index + 1] ||
      (entry.actor === null
        ? index !== 0
        : !isUserReference(entry.actor) ||
          (index > 0 &&
            entry.actor.id.toLowerCase() !==
              workOrder.assignedTechnician?.id.toLowerCase()))
    ) {
      throw new Error("The work-orders API returned an unexpected payload");
    }
    const transitionedAt = parseInstant(entry.transitionedAt);
    if (
      transitionedAt === null ||
      transitionedAt < previousAt ||
      transitionedAt > updatedAt
    ) {
      throw new Error("The work-orders API returned an unexpected payload");
    }
    previousAt = transitionedAt;
  }
  return { ...workOrder, history: history as WorkOrderHistory[] };
}

function parseWorkOrderList(value: unknown, limit: number): WorkOrderList {
  if (
    !isExactRecord(value, ["workOrders", "limit"]) ||
    value.limit !== limit ||
    !Array.isArray(value.workOrders) ||
    value.workOrders.length > limit
  ) {
    throw new Error("The work-orders endpoint returned an unexpected payload");
  }
  const workOrders: WorkOrder[] = [];
  const ids = new Set<string>();
  for (const candidate of value.workOrders) {
    const workOrder = parseWorkOrder(candidate);
    const id = workOrder.id.toLowerCase();
    if (ids.has(id)) {
      throw new Error(
        "The work-orders endpoint returned an unexpected payload",
      );
    }
    ids.add(id);
    workOrders.push(workOrder);
  }
  return { workOrders, limit };
}

function parseEligibleTechnicians(value: unknown): EligibleTechnicianList {
  if (
    !isExactRecord(value, ["technicians"]) ||
    !Array.isArray(value.technicians) ||
    value.technicians.length > 100 ||
    !value.technicians.every(isUserReference)
  ) {
    throw new Error(
      "The eligible-technicians endpoint returned an unexpected payload",
    );
  }
  const ids = new Set<string>();
  for (const technician of value.technicians as WorkOrderTechnician[]) {
    const id = technician.id.toLowerCase();
    if (ids.has(id)) {
      throw new Error(
        "The eligible-technicians endpoint returned an unexpected payload",
      );
    }
    ids.add(id);
  }
  return value as EligibleTechnicianList;
}

async function readJson(response: Response): Promise<unknown> {
  const mediaType = response.headers
    .get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The work-orders API returned an unexpected content type");
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
    const value: unknown = await response.json();
    return typeof value === "object" &&
      value !== null &&
      !Array.isArray(value) &&
      typeof (value as Record<string, unknown>).code === "string"
      ? ((value as Record<string, unknown>).code as string)
      : null;
  } catch {
    return null;
  }
}

function requireUuid(value: string, label: string): void {
  if (!UUID_PATTERN.test(value)) {
    throw new Error(`A valid ${label} identifier is required`);
  }
}

function classifyReadFailure(response: Response): void {
  if (response.status === 401) {
    throw new WorkOrderSessionExpiredError();
  }
  if (response.status === 403) {
    throw new WorkOrderForbiddenError();
  }
  if (response.status === 404) {
    throw new WorkOrderNotFoundError();
  }
  if (!response.ok) {
    throw new Error("The work-orders API returned an unsuccessful response");
  }
}

async function classifyMutationFailure(
  response: Response,
  command: "create" | "assign" | "start" | "complete",
): Promise<void> {
  if (response.status === 401) {
    throw new WorkOrderSessionExpiredError();
  }
  const code = await readProblemCode(response);
  if (response.status === 403) {
    if (code === "CSRF_REJECTED") {
      throw new WorkOrderRequestVerificationError();
    }
    throw new WorkOrderForbiddenError();
  }
  if (
    command === "create" &&
    response.status === 404 &&
    code === "WORK_ORDER_SOURCE_ALERT_NOT_FOUND"
  ) {
    throw new WorkOrderSourceAlertNotFoundError();
  }
  if (
    command === "create" &&
    response.status === 409 &&
    code === "WORK_ORDER_ALREADY_EXISTS"
  ) {
    throw new WorkOrderAlreadyExistsError();
  }
  if (
    command !== "create" &&
    response.status === 404 &&
    code === "WORK_ORDER_NOT_FOUND"
  ) {
    throw new WorkOrderNotFoundError();
  }
  if (
    command !== "create" &&
    response.status === 409 &&
    code === "WORK_ORDER_STATE_CONFLICT"
  ) {
    throw new WorkOrderStateConflictError();
  }
  if (
    command === "assign" &&
    response.status === 400 &&
    code === "INVALID_WORK_ORDER_ASSIGNEE"
  ) {
    throw new InvalidWorkOrderAssigneeError();
  }
  throw new WorkOrderCommandUncertainError();
}

export async function getWorkOrders(
  limit = DEFAULT_WORK_ORDER_LIMIT,
  signal?: AbortSignal,
): Promise<WorkOrderList> {
  if (
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > MAX_WORK_ORDER_LIMIT
  ) {
    throw new Error("A work-order limit from 1 through 100 is required");
  }
  const response = await fetch(`/api/v1/work-orders?limit=${limit}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });
  classifyReadFailure(response);
  return parseWorkOrderList(await readJson(response), limit);
}

export async function getWorkOrderDetail(
  workOrderId: string,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  requireUuid(workOrderId, "work-order");
  const response = await fetch(`/api/v1/work-orders/${workOrderId}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });
  classifyReadFailure(response);
  return parseWorkOrderDetail(await readJson(response), workOrderId);
}

export const getWorkOrder = getWorkOrderDetail;

export async function getEligibleTechnicians(
  signal?: AbortSignal,
): Promise<EligibleTechnicianList> {
  const response = await fetch("/api/v1/work-orders/eligible-technicians", {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });
  classifyReadFailure(response);
  return parseEligibleTechnicians(await readJson(response));
}

export async function createWorkOrder(
  alertId: string,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  requireUuid(alertId, "alert");
  let response: Response;
  try {
    response = await fetch("/api/v1/work-orders", {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      body: JSON.stringify({ alertId }),
      signal,
    });
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
  if (response.status !== 201) {
    await classifyMutationFailure(response, "create");
  }
  try {
    const workOrder = parseWorkOrderDetail(await readJson(response));
    if (
      workOrder.alertId.toLowerCase() !== alertId.toLowerCase() ||
      workOrder.status !== "OPEN"
    ) {
      throw new Error("unexpected created work order");
    }
    return workOrder;
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
}

export async function assignWorkOrder(
  workOrderId: string,
  technicianUserId: string,
  expectedVersion: number,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  requireUuid(workOrderId, "work-order");
  requireUuid(technicianUserId, "technician");
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("A non-negative expected work-order version is required");
  }
  let response: Response;
  try {
    response = await fetch(`/api/v1/work-orders/${workOrderId}/assign`, {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      body: JSON.stringify({ technicianUserId, expectedVersion }),
      signal,
    });
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
  if (response.status !== 200) {
    await classifyMutationFailure(response, "assign");
  }
  try {
    const workOrder = parseWorkOrderDetail(
      await readJson(response),
      workOrderId,
    );
    if (
      workOrder.status !== "ASSIGNED" ||
      workOrder.version !== expectedVersion + 1 ||
      workOrder.history[0]?.actor === null ||
      workOrder.assignedTechnician?.id.toLowerCase() !==
        technicianUserId.toLowerCase()
    ) {
      throw new Error("unexpected assigned work order");
    }
    return workOrder;
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
}

async function transitionWorkOrder(
  workOrderId: string,
  expectedVersion: number,
  command: "start" | "complete",
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  requireUuid(workOrderId, "work-order");
  if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 0) {
    throw new Error("A non-negative expected work-order version is required");
  }
  let response: Response;
  try {
    response = await fetch(`/api/v1/work-orders/${workOrderId}/${command}`, {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      credentials: "same-origin",
      body: JSON.stringify({ expectedVersion }),
      signal,
    });
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
  if (response.status !== 200) {
    await classifyMutationFailure(response, command);
  }
  try {
    const workOrder = parseWorkOrderDetail(
      await readJson(response),
      workOrderId,
    );
    if (
      workOrder.status !== (command === "start" ? "IN_PROGRESS" : "DONE") ||
      workOrder.version !== expectedVersion + 1
    ) {
      throw new Error("unexpected work-order transition");
    }
    return workOrder;
  } catch (error: unknown) {
    throw new WorkOrderCommandUncertainError({ cause: error });
  }
}

export function startWorkOrder(
  workOrderId: string,
  expectedVersion: number,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  return transitionWorkOrder(
    workOrderId,
    expectedVersion,
    "start",
    csrfToken,
    signal,
  );
}

export function completeWorkOrder(
  workOrderId: string,
  expectedVersion: number,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<WorkOrderDetail> {
  return transitionWorkOrder(
    workOrderId,
    expectedVersion,
    "complete",
    csrfToken,
    signal,
  );
}
