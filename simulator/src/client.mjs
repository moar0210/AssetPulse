import { isIsoInstant } from "./validation.mjs";

const JSON_MEDIA_TYPE = "application/json";
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HEADER_NAME_PATTERN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

class CookieJar {
  #cookies = new Map();

  capture(headers) {
    const setCookieValues =
      typeof headers.getSetCookie === "function"
        ? headers.getSetCookie()
        : [headers.get("set-cookie")].filter(Boolean);

    for (const value of setCookieValues) {
      const [pair, ...attributes] = value.split(";");
      const separator = pair.indexOf("=");

      if (separator <= 0) {
        continue;
      }

      const name = pair.slice(0, separator).trim();
      const cookieValue = pair.slice(separator + 1).trim();
      const maxAgeAttribute = attributes.find((attribute) =>
        attribute.trim().toLowerCase().startsWith("max-age="),
      );
      const rawMaxAge = maxAgeAttribute?.split("=", 2)[1]?.trim();
      const expired =
        rawMaxAge !== undefined &&
        /^-?\d+$/.test(rawMaxAge) &&
        BigInt(rawMaxAge) <= 0n;

      if (expired || cookieValue.length === 0) {
        this.#cookies.delete(name);
      } else {
        this.#cookies.set(name, cookieValue);
      }
    }
  }

  headerValue() {
    return [...this.#cookies.entries()]
      .map(([name, value]) => `${name}=${value}`)
      .join("; ");
  }
}

function isExactRecord(value, expectedKeys) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }

  const actualKeys = Object.keys(value);
  return (
    actualKeys.length === expectedKeys.length &&
    expectedKeys.every((key) => Object.hasOwn(value, key))
  );
}

function isNonEmptyText(value) {
  return typeof value === "string" && value.length > 0 && value.trim() === value;
}

function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

function isInstant(value) {
  return isIsoInstant(value);
}

function isSafeHeaderValue(value) {
  return isNonEmptyText(value) && /^[\x21-\x7e]+$/.test(value);
}

async function readJson(response, area) {
  let mediaType;
  try {
    mediaType = response.headers
      .get("content-type")
      ?.split(";", 1)[0]
      ?.trim()
      .toLowerCase();
  } catch {
    throw new Error(`${area} returned an unexpected content type`);
  }

  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error(`${area} returned an unexpected content type`);
  }

  try {
    return await response.json();
  } catch {
    throw new Error(`${area} returned malformed JSON`);
  }
}

function parseCsrf(payload) {
  if (
    !isExactRecord(payload, ["headerName", "token"]) ||
    !isNonEmptyText(payload.headerName) ||
    !HEADER_NAME_PATTERN.test(payload.headerName) ||
    !isSafeHeaderValue(payload.token)
  ) {
    throw new Error("The CSRF endpoint returned an unexpected payload");
  }

  return payload;
}

function parseIdentity(payload) {
  if (
    !isExactRecord(payload, [
      "userId",
      "displayName",
      "email",
      "organisation",
      "role",
    ]) ||
    !isUuid(payload.userId) ||
    !isNonEmptyText(payload.displayName) ||
    !isNonEmptyText(payload.email) ||
    !isExactRecord(payload.organisation, ["id", "slug", "name"]) ||
    !isUuid(payload.organisation.id) ||
    payload.organisation.slug !== "northstar-operations" ||
    !isNonEmptyText(payload.organisation.name) ||
    !isExactRecord(payload.role, ["code", "displayName"]) ||
    payload.role.code !== "OPERATIONS_ADMIN" ||
    !isNonEmptyText(payload.role.displayName)
  ) {
    throw new Error("Login returned an unexpected Operations Admin identity");
  }

  return payload;
}

function parseAcceptedBatch(payload, expectedRequest) {
  if (
    !isExactRecord(payload, [
      "batchId",
      "idempotencyKey",
      "readingCount",
      "acceptedAt",
    ]) ||
    !isUuid(payload.batchId) ||
    payload.idempotencyKey !== expectedRequest.idempotencyKey ||
    payload.readingCount !== expectedRequest.readings.length ||
    !isInstant(payload.acceptedAt)
  ) {
    throw new Error("Telemetry acceptance returned an unexpected payload");
  }

  return payload;
}

function validateConfiguration({ baseUrl, email, password, fetchImpl, timeoutMs }) {
  if (typeof baseUrl !== "string" || baseUrl.trim() !== baseUrl) {
    throw new Error("ASSETPULSE_BASE_URL must be an absolute HTTP origin");
  }

  let parsedBaseUrl;
  try {
    parsedBaseUrl = new URL(baseUrl);
  } catch {
    throw new Error("ASSETPULSE_BASE_URL must be an absolute HTTP origin");
  }

  if (
    !["http:", "https:"].includes(parsedBaseUrl.protocol) ||
    parsedBaseUrl.username !== "" ||
    parsedBaseUrl.password !== "" ||
    parsedBaseUrl.pathname !== "/" ||
    parsedBaseUrl.search !== "" ||
    parsedBaseUrl.hash !== "" ||
    baseUrl.includes("?") ||
    baseUrl.includes("#")
  ) {
    throw new Error("ASSETPULSE_BASE_URL must be an absolute HTTP origin");
  }

  if (!isNonEmptyText(email) || !isNonEmptyText(password)) {
    throw new Error("Simulator credentials must be non-empty");
  }

  if (typeof fetchImpl !== "function") {
    throw new Error("A fetch implementation is required");
  }

  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 60_000) {
    throw new Error("The request timeout must be between 1 and 60000 milliseconds");
  }

  return parsedBaseUrl;
}

export function createPublicApiClient({
  baseUrl,
  email,
  password,
  fetchImpl = globalThis.fetch,
  timeoutMs = 5_000,
}) {
  const parsedBaseUrl = validateConfiguration({
    baseUrl,
    email,
    password,
    fetchImpl,
    timeoutMs,
  });
  const cookies = new CookieJar();

  async function request(path, options) {
    let response;
    try {
      const headers = new Headers(options.headers);
      const cookie = cookies.headerValue();
      if (cookie !== "") {
        headers.set("Cookie", cookie);
      }

      response = await fetchImpl(new URL(path, parsedBaseUrl), {
        ...options,
        headers,
        redirect: "error",
        signal: AbortSignal.timeout(timeoutMs),
      });
      cookies.capture(response.headers);
    } catch {
      throw new Error(`Request to ${path} failed`);
    }

    return response;
  }

  async function getCsrfToken() {
    const response = await request("/api/v1/session/csrf", {
      method: "GET",
      headers: { Accept: JSON_MEDIA_TYPE },
    });

    if (response.status !== 200) {
      throw new Error(`CSRF bootstrap failed with HTTP ${response.status}`);
    }

    return parseCsrf(await readJson(response, "The CSRF endpoint"));
  }

  async function login(csrfToken) {
    const response = await request("/api/v1/session", {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      body: JSON.stringify({ email, password }),
    });

    if (response.status !== 200) {
      throw new Error(`Login failed with HTTP ${response.status}`);
    }

    return response;
  }

  async function acceptTelemetry(requestBody, csrfToken) {
    const response = await request("/api/v1/telemetry-batches", {
      method: "POST",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
      body: JSON.stringify(requestBody),
    });

    if (response.status !== 200) {
      throw new Error(`Telemetry acceptance failed with HTTP ${response.status}`);
    }

    return parseAcceptedBatch(
      await readJson(response, "Telemetry acceptance"),
      requestBody,
    );
  }

  async function logout(csrfToken) {
    const response = await request("/api/v1/session", {
      method: "DELETE",
      headers: {
        Accept: JSON_MEDIA_TYPE,
        [csrfToken.headerName]: csrfToken.token,
      },
    });

    if (response.status !== 204) {
      throw new Error(`Logout failed with HTTP ${response.status}`);
    }
  }

  return {
    async run(requestBody) {
      const initialCsrfToken = await getCsrfToken();
      const loginResponse = await login(initialCsrfToken);
      let activeCsrfToken = initialCsrfToken;
      let acceptedBatch;
      let primaryFailure;
      let csrfRefreshCompleted = false;

      try {
        parseIdentity(await readJson(loginResponse, "Login"));
        activeCsrfToken = await getCsrfToken();
        csrfRefreshCompleted = true;
        acceptedBatch = await acceptTelemetry(requestBody, activeCsrfToken);
      } catch (error) {
        primaryFailure = error;
      }

      try {
        if (!csrfRefreshCompleted) {
          activeCsrfToken = await getCsrfToken();
        }
        await logout(activeCsrfToken);
      } catch (error) {
        if (primaryFailure === undefined) {
          primaryFailure = error;
        }
      }

      if (primaryFailure !== undefined) {
        throw primaryFailure;
      }

      return acceptedBatch;
    },
  };
}
