import { parseInstant } from "./instant";

export const DEFAULT_TELEMETRY_LIMIT = 100;
export const MAX_TELEMETRY_LIMIT = 500;
export const MAX_TELEMETRY_RANGE_MS = 24 * 60 * 60 * 1_000;
export const MAX_TELEMETRY_VALUE = 1_000_000_000_000;

export type TelemetryReading = Readonly<{
  id: string;
  value: number;
  observedAt: string;
}>;

export type TelemetryRange = Readonly<{
  sensorId: string;
  from: string;
  to: string;
  readings: readonly TelemetryReading[];
}>;

export class TelemetrySensorNotFoundError extends Error {
  constructor() {
    super("Sensor telemetry not found");
    this.name = "TelemetrySensorNotFoundError";
  }
}

export class TelemetrySessionExpiredError extends Error {
  constructor() {
    super("The telemetry session has expired");
    this.name = "TelemetrySessionExpiredError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;
const MAX_TELEMETRY_RANGE_NANOSECONDS =
  BigInt(MAX_TELEMETRY_RANGE_MS) * NANOSECONDS_PER_MILLISECOND;

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

function isTelemetryReading(
  value: unknown,
  fromEpoch: bigint,
  toEpoch: bigint,
): value is TelemetryReading {
  if (!isExactRecord(value, ["id", "value", "observedAt"])) {
    return false;
  }

  const observedAt = parseInstant(value.observedAt);
  return (
    isUuid(value.id) &&
    typeof value.value === "number" &&
    Number.isFinite(value.value) &&
    Math.abs(value.value) <= MAX_TELEMETRY_VALUE &&
    observedAt !== null &&
    observedAt >= fromEpoch &&
    observedAt < toEpoch
  );
}

function isChronological(readings: readonly TelemetryReading[]): boolean {
  for (let index = 1; index < readings.length; index += 1) {
    const previous = readings[index - 1];
    const current = readings[index];
    const previousTime = parseInstant(previous.observedAt);
    const currentTime = parseInstant(current.observedAt);

    if (
      previousTime === null ||
      currentTime === null ||
      previousTime > currentTime ||
      (previousTime === currentTime &&
        previous.id.toLowerCase() > current.id.toLowerCase())
    ) {
      return false;
    }
  }

  return true;
}

function hasUniqueReadingIds(readings: readonly unknown[]): boolean {
  const readingIds = new Set<string>();

  for (const reading of readings) {
    if (
      !isExactRecord(reading, ["id", "value", "observedAt"]) ||
      !isUuid(reading.id)
    ) {
      return false;
    }

    const readingId = reading.id.toLowerCase();
    if (readingIds.has(readingId)) {
      return false;
    }
    readingIds.add(readingId);
  }

  return true;
}

function parseTelemetryRange(
  value: unknown,
  expectedSensorId: string,
  expectedFromEpoch: bigint,
  expectedToEpoch: bigint,
  limit: number,
): TelemetryRange {
  if (
    !isExactRecord(value, ["sensorId", "from", "to", "readings"]) ||
    value.sensorId !== expectedSensorId ||
    parseInstant(value.from) !== expectedFromEpoch ||
    parseInstant(value.to) !== expectedToEpoch ||
    !Array.isArray(value.readings) ||
    value.readings.length > limit ||
    !hasUniqueReadingIds(value.readings) ||
    !value.readings.every((reading) =>
      isTelemetryReading(reading, expectedFromEpoch, expectedToEpoch),
    ) ||
    !isChronological(value.readings)
  ) {
    throw new Error("The telemetry endpoint returned an unexpected payload");
  }

  return value as TelemetryRange;
}

async function readJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type");
  const mediaType = contentType?.split(";", 1)[0]?.trim().toLowerCase();

  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The telemetry API returned an unexpected content type");
  }

  return response.json() as Promise<unknown>;
}

export async function getTelemetryReadings(
  sensorId: string,
  from: string,
  to: string,
  limit = DEFAULT_TELEMETRY_LIMIT,
  signal?: AbortSignal,
): Promise<TelemetryRange> {
  const fromEpoch = parseInstant(from);
  const toEpoch = parseInstant(to);

  if (!UUID_PATTERN.test(sensorId)) {
    throw new Error("A valid sensor identifier is required");
  }

  if (
    fromEpoch === null ||
    toEpoch === null ||
    fromEpoch >= toEpoch ||
    toEpoch - fromEpoch > MAX_TELEMETRY_RANGE_NANOSECONDS
  ) {
    throw new Error("A valid telemetry range of at most 24 hours is required");
  }

  if (
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > MAX_TELEMETRY_LIMIT
  ) {
    throw new Error("A telemetry limit from 1 through 500 is required");
  }

  const search = new URLSearchParams({ from, to, limit: String(limit) });
  const response = await fetch(
    `/api/v1/sensors/${sensorId}/telemetry-readings?${search.toString()}`,
    {
      method: "GET",
      headers: { Accept: JSON_MEDIA_TYPE },
      credentials: "same-origin",
      signal,
    },
  );

  if (response.status === 401) {
    throw new TelemetrySessionExpiredError();
  }

  if (response.status === 404) {
    throw new TelemetrySensorNotFoundError();
  }

  if (!response.ok) {
    throw new Error("The telemetry endpoint returned an unsuccessful response");
  }

  return parseTelemetryRange(
    await readJson(response),
    sensorId,
    fromEpoch,
    toEpoch,
    limit,
  );
}
