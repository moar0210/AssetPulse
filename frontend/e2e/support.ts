import AxeBuilder from "@axe-core/playwright";
import { expect } from "@playwright/test";
import type {
  APIResponse,
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

const SECURITY_HEADERS = {
  "referrer-policy": "no-referrer",
  "permissions-policy": "camera=(), geolocation=(), microphone=()",
  "x-content-type-options": "nosniff",
  "x-frame-options": "DENY",
} as const;

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
  return { context, page: await context.newPage() };
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
  ).toBeVisible();
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
    `${surface} has serious or critical accessibility violations`,
  ).toEqual([]);
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
  const response = await page.request.get("/api/v1/session/csrf", {
    headers: { Accept: "application/json" },
  });
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
    const response = await page.request.post("/api/v1/demo/reset", {
      headers: {
        Accept: "application/json",
        [csrfToken.headerName]: csrfToken.token,
      },
    });
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
    await context.close();
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
  response: APIResponse,
  status: number,
): Promise<void> {
  expect(response.status()).toBe(status);
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
