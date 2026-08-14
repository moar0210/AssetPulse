export const MAX_ASSETS = 100;
export const MAX_SENSORS_PER_ASSET = 100;
export const MAX_THRESHOLD_RULES_PER_SENSOR = 100;
export const MAX_THRESHOLD_VALUE = 1_000_000_000_000;
export const MAX_COOLDOWN_SECONDS = 604_800;

export type AssetSummary = Readonly<{
  id: string;
  assetCode: string;
  name: string;
}>;

export type MeasurementType = "TEMPERATURE";
export type MeasurementUnit = "CELSIUS";
export type ThresholdComparison = "GREATER_THAN_OR_EQUAL_TO";

export type ThresholdRule = Readonly<{
  id: string;
  ruleCode: string;
  name: string;
  comparison: ThresholdComparison;
  thresholdValue: number;
  cooldownSeconds: number;
  enabled: boolean;
}>;

export type Sensor = Readonly<{
  id: string;
  sensorKey: string;
  name: string;
  measurementType: MeasurementType;
  unit: MeasurementUnit;
  thresholdRules: readonly ThresholdRule[];
}>;

export type AssetDetail = Readonly<{
  id: string;
  assetCode: string;
  name: string;
  sensors: readonly Sensor[];
}>;

export class AssetNotFoundError extends Error {
  constructor() {
    super("Asset not found");
    this.name = "AssetNotFoundError";
  }
}

export class AssetSessionExpiredError extends Error {
  constructor() {
    super("The asset session has expired");
    this.name = "AssetSessionExpiredError";
  }
}

type AssetListResponse = Readonly<{
  assets: readonly AssetSummary[];
}>;

const JSON_MEDIA_TYPE = "application/json";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CONFIGURATION_KEY_PATTERN = /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/;

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

function isAssetSummary(value: unknown): value is AssetSummary {
  return (
    isExactRecord(value, ["id", "assetCode", "name"]) &&
    typeof value.id === "string" &&
    UUID_PATTERN.test(value.id) &&
    isBoundedTrimmedText(value.assetCode, 64) &&
    isBoundedTrimmedText(value.name, 120)
  );
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isThresholdRule(value: unknown): value is ThresholdRule {
  return (
    isExactRecord(value, [
      "id",
      "ruleCode",
      "name",
      "comparison",
      "thresholdValue",
      "cooldownSeconds",
      "enabled",
    ]) &&
    isUuid(value.id) &&
    isBoundedTrimmedText(value.ruleCode, 64) &&
    CONFIGURATION_KEY_PATTERN.test(value.ruleCode) &&
    isBoundedTrimmedText(value.name, 120) &&
    value.comparison === "GREATER_THAN_OR_EQUAL_TO" &&
    typeof value.thresholdValue === "number" &&
    Number.isFinite(value.thresholdValue) &&
    value.thresholdValue >= -MAX_THRESHOLD_VALUE &&
    value.thresholdValue <= MAX_THRESHOLD_VALUE &&
    typeof value.cooldownSeconds === "number" &&
    Number.isSafeInteger(value.cooldownSeconds) &&
    value.cooldownSeconds >= 0 &&
    value.cooldownSeconds <= MAX_COOLDOWN_SECONDS &&
    typeof value.enabled === "boolean"
  );
}

function isSensor(value: unknown): value is Sensor {
  return (
    isExactRecord(value, [
      "id",
      "sensorKey",
      "name",
      "measurementType",
      "unit",
      "thresholdRules",
    ]) &&
    isUuid(value.id) &&
    isBoundedTrimmedText(value.sensorKey, 64) &&
    CONFIGURATION_KEY_PATTERN.test(value.sensorKey) &&
    isBoundedTrimmedText(value.name, 120) &&
    value.measurementType === "TEMPERATURE" &&
    value.unit === "CELSIUS" &&
    Array.isArray(value.thresholdRules) &&
    value.thresholdRules.length <= MAX_THRESHOLD_RULES_PER_SENSOR &&
    value.thresholdRules.every(isThresholdRule)
  );
}

function isAssetDetail(value: unknown): value is AssetDetail {
  return (
    isExactRecord(value, ["id", "assetCode", "name", "sensors"]) &&
    isUuid(value.id) &&
    isBoundedTrimmedText(value.assetCode, 64) &&
    isBoundedTrimmedText(value.name, 120) &&
    Array.isArray(value.sensors) &&
    value.sensors.length <= MAX_SENSORS_PER_ASSET &&
    value.sensors.every(isSensor)
  );
}

function isAssetListResponse(value: unknown): value is AssetListResponse {
  return (
    isExactRecord(value, ["assets"]) &&
    Array.isArray(value.assets) &&
    value.assets.length <= MAX_ASSETS &&
    value.assets.every(isAssetSummary)
  );
}

async function readJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type");
  const mediaType = contentType?.split(";", 1)[0]?.trim().toLowerCase();

  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The assets API returned an unexpected content type");
  }

  return response.json() as Promise<unknown>;
}

export async function getAssets(
  signal?: AbortSignal,
): Promise<readonly AssetSummary[]> {
  const response = await fetch("/api/v1/assets", {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });

  if (response.status === 401) {
    throw new AssetSessionExpiredError();
  }

  if (!response.ok) {
    throw new Error("The assets endpoint returned an unsuccessful response");
  }

  const payload = await readJson(response);

  if (!isAssetListResponse(payload)) {
    throw new Error("The assets endpoint returned an unexpected payload");
  }

  return payload.assets;
}

export async function getAssetDetail(
  assetId: string,
  signal?: AbortSignal,
): Promise<AssetDetail> {
  if (!UUID_PATTERN.test(assetId)) {
    throw new Error("A valid asset identifier is required");
  }

  const response = await fetch(`/api/v1/assets/${assetId}`, {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });

  if (response.status === 401) {
    throw new AssetSessionExpiredError();
  }

  if (response.status === 404) {
    throw new AssetNotFoundError();
  }

  if (!response.ok) {
    throw new Error(
      "The asset detail endpoint returned an unsuccessful response",
    );
  }

  const payload = await readJson(response);

  if (!isAssetDetail(payload) || payload.id !== assetId) {
    throw new Error("The asset detail endpoint returned an unexpected payload");
  }

  return payload;
}
