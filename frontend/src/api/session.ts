export const roleCodes = ["OPERATIONS_ADMIN", "TECHNICIAN", "VIEWER"] as const;

export type RoleCode = (typeof roleCodes)[number];

export type CsrfToken = Readonly<{
  headerName: string;
  token: string;
}>;

export type SessionIdentity = Readonly<{
  userId: string;
  displayName: string;
  email: string;
  organisation: Readonly<{
    id: string;
    slug: string;
    name: string;
  }>;
  role: Readonly<{
    code: RoleCode;
    displayName: string;
  }>;
}>;

export type LoginRequest = Readonly<{
  email: string;
  password: string;
}>;

export class AuthenticationFailedError extends Error {
  constructor() {
    super("Authentication failed");
    this.name = "AuthenticationFailedError";
  }
}

export class LoginRateLimitedError extends Error {
  readonly retryAfterSeconds: number | null;

  constructor(retryAfterSeconds: number | null) {
    super("Login rate limited");
    this.name = "LoginRateLimitedError";
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export class RequestVerificationFailedError extends Error {
  constructor() {
    super("Request verification failed");
    this.name = "RequestVerificationFailedError";
  }
}

const JSON_MEDIA_TYPE = "application/json";
const MAX_LOGIN_RETRY_AFTER_SECONDS = 24 * 60 * 60;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
const HEADER_NAME_PATTERN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

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

function isTrimmedText(value: unknown): value is string {
  return (
    typeof value === "string" && value.length > 0 && value.trim() === value
  );
}

function isCsrfToken(value: unknown): value is CsrfToken {
  return (
    isExactRecord(value, ["headerName", "token"]) &&
    isTrimmedText(value.headerName) &&
    HEADER_NAME_PATTERN.test(value.headerName) &&
    isTrimmedText(value.token)
  );
}

function isOrganisation(
  value: unknown,
): value is SessionIdentity["organisation"] {
  return (
    isExactRecord(value, ["id", "slug", "name"]) &&
    typeof value.id === "string" &&
    UUID_PATTERN.test(value.id) &&
    typeof value.slug === "string" &&
    SLUG_PATTERN.test(value.slug) &&
    isTrimmedText(value.name)
  );
}

function isRole(value: unknown): value is SessionIdentity["role"] {
  return (
    isExactRecord(value, ["code", "displayName"]) &&
    typeof value.code === "string" &&
    roleCodes.some((roleCode) => roleCode === value.code) &&
    isTrimmedText(value.displayName)
  );
}

function isSessionIdentity(value: unknown): value is SessionIdentity {
  return (
    isExactRecord(value, [
      "userId",
      "displayName",
      "email",
      "organisation",
      "role",
    ]) &&
    typeof value.userId === "string" &&
    UUID_PATTERN.test(value.userId) &&
    isTrimmedText(value.displayName) &&
    isTrimmedText(value.email) &&
    value.email.includes("@") &&
    isOrganisation(value.organisation) &&
    isRole(value.role)
  );
}

function readLoginRetryAfterSeconds(response: Response): number | null {
  const retryAfter = response.headers.get("retry-after");

  if (retryAfter === null || !/^[1-9]\d{0,4}$/.test(retryAfter)) {
    return null;
  }

  const seconds = Number(retryAfter);
  return seconds <= MAX_LOGIN_RETRY_AFTER_SECONDS ? seconds : null;
}

async function readJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type");
  const mediaType = contentType?.split(";", 1)[0]?.trim().toLowerCase();

  if (mediaType !== JSON_MEDIA_TYPE) {
    throw new Error("The session API returned an unexpected content type");
  }

  return response.json() as Promise<unknown>;
}

async function readIdentity(response: Response): Promise<SessionIdentity> {
  if (!response.ok) {
    throw new Error("The session API returned an unsuccessful response");
  }

  const payload = await readJson(response);

  if (!isSessionIdentity(payload)) {
    throw new Error("The session API returned an unexpected identity payload");
  }

  return payload;
}

export async function getCsrfToken(signal?: AbortSignal): Promise<CsrfToken> {
  const response = await fetch("/api/v1/session/csrf", {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });

  if (!response.ok) {
    throw new Error("The CSRF endpoint returned an unsuccessful response");
  }

  const payload = await readJson(response);

  if (!isCsrfToken(payload)) {
    throw new Error("The CSRF endpoint returned an unexpected payload");
  }

  return payload;
}

export async function getCurrentSession(
  signal?: AbortSignal,
): Promise<SessionIdentity | null> {
  const response = await fetch("/api/v1/session", {
    method: "GET",
    headers: { Accept: JSON_MEDIA_TYPE },
    credentials: "same-origin",
    signal,
  });

  if (response.status === 401) {
    return null;
  }

  return readIdentity(response);
}

export async function login(
  request: LoginRequest,
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<SessionIdentity> {
  const response = await fetch("/api/v1/session", {
    method: "POST",
    headers: {
      Accept: JSON_MEDIA_TYPE,
      "Content-Type": JSON_MEDIA_TYPE,
      [csrfToken.headerName]: csrfToken.token,
    },
    credentials: "same-origin",
    body: JSON.stringify(request),
    signal,
  });

  if (response.status === 401) {
    throw new AuthenticationFailedError();
  }

  if (response.status === 429) {
    throw new LoginRateLimitedError(readLoginRetryAfterSeconds(response));
  }

  if (response.status === 403) {
    throw new RequestVerificationFailedError();
  }

  return readIdentity(response);
}

export async function logout(
  csrfToken: CsrfToken,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch("/api/v1/session", {
    method: "DELETE",
    headers: {
      Accept: JSON_MEDIA_TYPE,
      [csrfToken.headerName]: csrfToken.token,
    },
    credentials: "same-origin",
    signal,
  });

  if (response.status === 403) {
    throw new RequestVerificationFailedError();
  }

  if (response.status !== 204) {
    throw new Error("The logout endpoint returned an unexpected response");
  }
}
