import AxeBuilder from "@axe-core/playwright";
import { expect } from "@playwright/test";
import type {
  Browser,
  BrowserContext,
  Locator,
  Page,
  Response,
} from "@playwright/test";

export const SEEDED_PASSWORD = "AssetPulse1!";

export const seededAccounts = {
  northstarAdmin: {
    email: "admin@northstar.example",
    displayName: "Nora Admin",
    organisation: "Northstar Operations",
    role: "Operations Admin",
  },
  northstarTechnician: {
    email: "technician@northstar.example",
    displayName: "Theo Technician",
    organisation: "Northstar Operations",
    role: "Technician",
  },
  northstarViewer: {
    email: "viewer@northstar.example",
    displayName: "Vera Viewer",
    organisation: "Northstar Operations",
    role: "Viewer",
  },
  riversideAdmin: {
    email: "admin@riverside.example",
    displayName: "Riley Admin",
    organisation: "Riverside Manufacturing",
    role: "Operations Admin",
  },
} as const;

export type SeededAccount =
  (typeof seededAccounts)[keyof typeof seededAccounts];

export type CsrfToken = Readonly<{
  headerName: string;
  token: string;
}>;

export type DemoResetResult = Readonly<{
  resetAt: string;
  alertsResolved: number;
  workOrdersCompleted: number;
}>;

export type BrowserApiResponse = Readonly<{
  status(): number;
  headers(): Record<string, string>;
  json(): Promise<unknown>;
  url(): string;
}>;

export async function browserApiRequest(
  page: Page,
  method: "GET" | "POST",
  path: string,
  options: Readonly<{
    headers?: Readonly<Record<string, string>>;
    data?: unknown;
  }> = {},
): Promise<BrowserApiResponse> {
  const result = await page.evaluate(
    async ({ method, path, headers, data }) => {
      const request: RequestInit = {
        method,
        credentials: "same-origin",
        headers,
      };
      if (data !== undefined) request.body = JSON.stringify(data);
      const response = await fetch(path, request);
      return {
        status: response.status,
        headers: Object.fromEntries(response.headers.entries()),
        body: await response.json(),
        url: response.url,
      };
    },
    { method, path, headers: options.headers, data: options.data },
  );
  if (result.status >= 400) {
    const failures = expectedBrowserHttpFailures.get(page) ?? new Set<string>();
    failures.add(`${result.status}|${result.url}`);
    expectedBrowserHttpFailures.set(page, failures);
  }
  return {
    status: () => result.status,
    headers: () => result.headers,
    json: async () => result.body,
    url: () => result.url,
  };
}

const SECURITY_HEADERS = {
  "referrer-policy": "no-referrer",
  "permissions-policy": "camera=(), geolocation=(), microphone=()",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
} as const;

type BrowserDiagnostic = Readonly<{
  type: string;
  text: string;
  url: string;
}>;
const browserDiagnostics = new WeakMap<Page, BrowserDiagnostic[]>();
const expectedBrowserHttpFailures = new WeakMap<Page, Set<string>>();

export function monitorBrowser(page: Page): void {
  if (browserDiagnostics.has(page)) return;
  const diagnostics: BrowserDiagnostic[] = [];
  browserDiagnostics.set(page, diagnostics);
  page.on("console", (message) => {
    if (message.type() === "warning" || message.type() === "error") {
      diagnostics.push({
        type: message.type(),
        text: message.text(),
        url: message.location().url,
      });
    }
  });
  page.on("pageerror", (error) => {
    diagnostics.push({ type: "pageerror", text: error.message, url: "" });
  });
}

export function expectCleanBrowser(
  page: Page,
  expectedConflictPath?: string,
): void {
  const diagnostics = browserDiagnostics.get(page);
  expect(diagnostics, "Browser diagnostics must be monitored").toBeDefined();
  const unexpected = diagnostics!.filter((diagnostic) => {
    if (diagnostic.type !== "error" || diagnostic.url === "") return true;
    const path = new URL(diagnostic.url).pathname;
    // Chromium also reports expected anonymous discovery and the verified conflict as resource errors.
    const anonymousDiscovery =
      path === "/api/v1/session" &&
      /^Failed to load resource: the server responded with a status of 401 \((?:Unauthorized)?\)$/.test(
        diagnostic.text,
      );
    const rejectedConflict =
      expectedConflictPath !== undefined &&
      path === expectedConflictPath &&
      /^Failed to load resource: the server responded with a status of 409 \((?:Conflict)?\)$/.test(
        diagnostic.text,
      );
    const statusMatch = /status of ([0-9]{3}) \(/.exec(diagnostic.text);
    const verifiedDirectProbe =
      statusMatch !== null &&
      expectedBrowserHttpFailures
        .get(page)
        ?.has(`${statusMatch[1]}|${diagnostic.url}`) === true;
    return !anonymousDiscovery && !rejectedConflict && !verifiedDirectProbe;
  });
  expect(unexpected, "Unexpected browser warnings or errors").toEqual([]);
}

function requireBaseURL(baseURL: string | undefined): string {
  if (baseURL === undefined || baseURL.trim() === "") {
    throw new Error("Playwright requires a configured baseURL");
  }
  return baseURL;
}

export async function newIsolatedPage(
  browser: Browser,
  baseURL: string | undefined,
): Promise<Readonly<{ context: BrowserContext; page: Page }>> {
  const context = await browser.newContext({
    baseURL: requireBaseURL(baseURL),
    locale: "en-US",
    timezoneId: "UTC",
  });
  const page = await context.newPage();
  monitorBrowser(page);
  return { context, page };
}

export async function openAndSignIn(
  page: Page,
  account: SeededAccount,
): Promise<Response | null> {
  const documentResponse = await page.goto("/", {
    waitUntil: "domcontentloaded",
  });
  const email = page.getByLabel("Email");
  const password = page.getByLabel("Password");

  await expect(email).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(email).toBeFocused();
  await page.keyboard.insertText(account.email);
  await page.keyboard.press("Tab");
  await expect(password).toBeFocused();
  await page.keyboard.insertText(SEEDED_PASSWORD);
  await page.keyboard.press("Enter");

  await expect(
    page.getByRole("heading", {
      level: 1,
      name: `Welcome, ${account.displayName}`,
    }),
  ).toBeVisible({ timeout: 35_000 });
  await expect(
    page.getByText(account.organisation, { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(account.role, { exact: true })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Dashboard", exact: true }),
  ).toHaveAttribute("aria-current", "page");

  return documentResponse;
}

export async function activateWithKeyboard(
  locator: Locator,
  key: "Enter" | "Space" = "Enter",
): Promise<void> {
  await locator.focus();
  await expect(locator).toBeFocused();
  await locator.press(key);
}

export async function openProductSection(
  page: Page,
  name: "Dashboard" | "Assets" | "Alerts" | "Work orders" | "Operations",
  key: "Enter" | "Space" = "Enter",
): Promise<void> {
  const navigation = page.getByRole("navigation", { name: "Product sections" });
  const button = navigation.getByRole("button", { name, exact: true });
  await activateWithKeyboard(button, key);
  await expect(button).toHaveAttribute("aria-current", "page");
}

export async function expectNoSeriousAccessibilityViolations(
  page: Page,
  surface: string,
): Promise<void> {
  const originalViewport = page.viewportSize();
  try {
    for (const viewport of [
      { width: 1280, height: 900 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport);
      await expect
        .poll(
          () =>
            page.evaluate(
              () =>
                Math.max(
                  document.documentElement.scrollWidth,
                  document.body.scrollWidth,
                ) - document.documentElement.clientWidth,
            ),
          { message: `${surface} overflows at ${viewport.width}px` },
        )
        .toBeLessThanOrEqual(1);
      const results = await new AxeBuilder({ page }).analyze();
      const blockingViolations = results.violations
        .filter(({ impact }) => impact === "critical" || impact === "serious")
        .map(({ id, impact, help, nodes }) => ({
          id,
          impact,
          help,
          targets: nodes.map(({ target }) => target),
        }));
      expect(
        blockingViolations,
        `${surface} has serious or critical accessibility violations at ${viewport.width}px`,
      ).toEqual([]);
    }
  } finally {
    if (originalViewport !== null) await page.setViewportSize(originalViewport);
  }
}

export function expectDocumentSecurityHeaders(response: Response | null): void {
  expect(
    response,
    "The document navigation should have an HTTP response",
  ).not.toBeNull();
  const headers = response!.headers();
  const configuredHeaders = [
    "content-security-policy",
    ...Object.keys(SECURITY_HEADERS),
  ].filter((name) => headers[name] !== undefined);
  const headersAreRequired =
    process.env.ASSETPULSE_EXPECT_SECURITY_HEADERS === "true" ||
    configuredHeaders.length > 0;

  if (!headersAreRequired) {
    return;
  }

  const contentSecurityPolicy = headers["content-security-policy"];
  expect(contentSecurityPolicy).toContain("default-src 'self'");
  expect(contentSecurityPolicy).toContain("frame-ancestors 'none'");
  expect(contentSecurityPolicy).toContain("object-src 'none'");
  for (const [name, expectedValue] of Object.entries(SECURITY_HEADERS)) {
    expect(headers[name], `${name} response header`).toBe(expectedValue);
  }
}

export async function getCsrfToken(page: Page): Promise<CsrfToken> {
  const response = await browserApiRequest(
    page,
    "GET",
    "/api/v1/session/csrf",
    {
      headers: { Accept: "application/json" },
    },
  );
  expect(response.status()).toBe(200);
  const payload: unknown = await response.json();
  expect(payload).toEqual({
    headerName: expect.any(String),
    token: expect.any(String),
  });
  const token = payload as CsrfToken;
  expect(token.headerName.trim()).not.toBe("");
  expect(token.token.trim()).not.toBe("");
  return token;
}

export async function resetDemoThroughApi(
  browser: Browser,
  baseURL: string | undefined,
): Promise<DemoResetResult> {
  const { context, page } = await newIsolatedPage(browser, baseURL);
  try {
    await openAndSignIn(page, seededAccounts.northstarAdmin);
    const csrfToken = await getCsrfToken(page);
    const response = await browserApiRequest(
      page,
      "POST",
      "/api/v1/demo/reset",
      {
        headers: {
          Accept: "application/json",
          [csrfToken.headerName]: csrfToken.token,
        },
      },
    );
    expect(response.status()).toBe(200);
    const payload: unknown = await response.json();
    expect(payload).toEqual({
      resetAt: expect.any(String),
      alertsResolved: expect.any(Number),
      workOrdersCompleted: expect.any(Number),
    });
    const result = payload as DemoResetResult;
    expect(Number.isSafeInteger(result.alertsResolved)).toBe(true);
    expect(result.alertsResolved).toBeGreaterThanOrEqual(0);
    expect(Number.isSafeInteger(result.workOrdersCompleted)).toBe(true);
    expect(result.workOrdersCompleted).toBeGreaterThanOrEqual(0);
    return result;
  } finally {
    try {
      expectCleanBrowser(page);
    } finally {
      await context.close();
    }
  }
}

export function responseMatches(
  response: Response,
  method: string,
  pathname: string | RegExp,
): boolean {
  const actualPath = new URL(response.url()).pathname;
  return (
    response.request().method() === method &&
    (typeof pathname === "string"
      ? actualPath === pathname
      : pathname.test(actualPath))
  );
}

export async function expectApiStatus(
  response: BrowserApiResponse,
  status: number,
): Promise<void> {
  expect(response.status()).toBe(status);
}

export async function expectApiProblem(
  response: BrowserApiResponse,
  code:
    | "ACCESS_DENIED"
    | "ALERT_NOT_FOUND"
    | "WORK_ORDER_NOT_FOUND"
    | "ASSET_NOT_FOUND",
): Promise<void> {
  const contracts = {
    ACCESS_DENIED: {
      status: 403,
      title: "Access denied",
      detail: "The authenticated user cannot access this resource.",
    },
    ALERT_NOT_FOUND: {
      status: 404,
      title: "Alert not found",
      detail: "The requested alert does not exist or is not accessible.",
    },
    WORK_ORDER_NOT_FOUND: {
      status: 404,
      title: "Work order not found",
      detail: "The requested work order does not exist or is not accessible.",
    },
    ASSET_NOT_FOUND: {
      status: 404,
      title: "Asset not found",
      detail: "The requested asset does not exist or is not accessible.",
    },
  } as const;
  expect(response.status()).toBe(contracts[code].status);
  expect(response.headers()["content-type"]).toContain(
    "application/problem+json",
  );
  expect(await response.json()).toEqual({
    ...contracts[code],
    type: `urn:assetpulse:problem:${code.toLowerCase().replaceAll("_", "-")}`,
    instance: new URL(response.url()).pathname,
    code,
    correlationId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
  });
}

export async function readJsonRecord(
  response: Readonly<{ json(): Promise<unknown> }>,
): Promise<Record<string, unknown>> {
  const payload = await response.json();
  expect(payload).not.toBeNull();
  expect(Array.isArray(payload)).toBe(false);
  expect(typeof payload).toBe("object");
  return payload as Record<string, unknown>;
}
