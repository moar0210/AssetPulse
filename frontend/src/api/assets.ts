export const MAX_ASSETS = 100;

export type AssetSummary = Readonly<{
  id: string;
  assetCode: string;
  name: string;
}>;

type AssetListResponse = Readonly<{
  assets: readonly AssetSummary[];
}>;

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

  if (!response.ok) {
    throw new Error("The assets endpoint returned an unsuccessful response");
  }

  const payload = await readJson(response);

  if (!isAssetListResponse(payload)) {
    throw new Error("The assets endpoint returned an unexpected payload");
  }

  return payload.assets;
}
