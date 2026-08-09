export type ApiStatusResponse = Readonly<{
  status: "available";
}>;

function isApiStatusResponse(value: unknown): value is ApiStatusResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const candidate = value as Record<string, unknown>;

  return (
    Object.keys(candidate).length === 1 && candidate.status === "available"
  );
}

export async function getApiStatus(
  signal: AbortSignal,
): Promise<ApiStatusResponse> {
  const response = await fetch("/api/v1/status", {
    method: "GET",
    headers: {
      Accept: "application/json",
    },
    signal,
  });

  if (!response.ok) {
    throw new Error("The status endpoint returned an unsuccessful response");
  }

  const contentType = response.headers.get("content-type");
  const mediaType = contentType?.split(";", 1)[0]?.trim().toLowerCase();

  if (mediaType !== "application/json") {
    throw new Error("The status endpoint returned an unexpected content type");
  }

  const payload: unknown = await response.json();

  if (!isApiStatusResponse(payload)) {
    throw new Error("The status endpoint returned an unexpected payload");
  }

  return payload;
}
