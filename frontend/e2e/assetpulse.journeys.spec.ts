import { expect, test } from "@playwright/test";
import type { Page, Response } from "@playwright/test";

import {
  activateWithKeyboard,
  browserApiRequest,
  expectApiProblem,
  expectApiStatus,
  expectCleanBrowser,
  expectDocumentSecurityHeaders,
  expectNoSeriousAccessibilityViolations,
  getCsrfToken,
  monitorBrowser,
  newIsolatedPage,
  openAndSignIn,
  openProductSection,
  readJsonRecord,
  resetDemoThroughApi,
  responseMatches,
  seededAccounts,
} from "./support";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ALERT_DETAIL_PATH = /^\/api\/v1\/alerts\/[0-9a-f-]{36}$/i;
const ALERT_ROW_NAMES = {
  open: "View open alert for Boiler Feed Pump: High bearing temperature",
  acknowledged:
    "View acknowledged alert for Boiler Feed Pump: High bearing temperature",
} as const;
const WORK_ORDER_ROW_NAMES = {
  open: "View open work order for Boiler Feed Pump: High bearing temperature",
  assigned:
    "View assigned work order for Boiler Feed Pump: High bearing temperature",
  done: "View done work order for Boiler Feed Pump: High bearing temperature",
} as const;

let alertId = "";
let workOrderId = "";
let cleanupComplete = false;

function expectUuid(value: unknown, label: string): asserts value is string {
  expect(typeof value, label).toBe("string");
  expect(String(value), label).toMatch(UUID_PATTERN);
}

function workOrderFact(page: Page, term: string) {
  return page
    .locator("dl.work-order-facts > div")
    .filter({ has: page.locator("dt", { hasText: new RegExp(`^${term}$`) }) })
    .locator("dd");
}

function assetNames(payload: Record<string, unknown>): string[] {
  expect(Array.isArray(payload.assets)).toBe(true);
  return (payload.assets as unknown[]).map((asset) => {
    expect(asset).not.toBeNull();
    expect(Array.isArray(asset)).toBe(false);
    expect(typeof asset).toBe("object");
    const name = (asset as Record<string, unknown>).name;
    expect(typeof name).toBe("string");
    return String(name);
  });
}

async function openCurrentWorkOrder(
  page: Page,
  rowName: string,
): Promise<void> {
  await openProductSection(page, "Work orders");
  const row = page.getByRole("button", { name: rowName, exact: true });
  await expect(row).toBeVisible();
  await activateWithKeyboard(row);
  await expect(
    page.getByRole("heading", {
      level: 2,
      name: "High bearing temperature",
    }),
  ).toBeVisible();
  await expect(
    page.locator('[aria-labelledby="work-order-detail-title"]'),
  ).toBeFocused();
}

test.describe("AssetPulse v0.6 product journeys", () => {
  test.describe.configure({ mode: "serial" });

  test.beforeEach(({ page }) => monitorBrowser(page));
  test.afterEach(({ page }) => expectCleanBrowser(page));

  test.afterAll(async ({ browser, baseURL }) => {
    if (!cleanupComplete) {
      await resetDemoThroughApi(browser, baseURL);
    }
  });

  test("live incident: reset, telemetry, alert, and assigned work remain keyboard accessible", async ({
    page,
    browser,
    baseURL,
  }) => {
    const loginDocument = await page.goto("/", {
      waitUntil: "domcontentloaded",
    });
    await expect(page.getByLabel("Email")).toBeVisible();
    expectDocumentSecurityHeaders(loginDocument);
    await expectNoSeriousAccessibilityViolations(page, "sign-in screen");

    await openAndSignIn(page, seededAccounts.northstarAdmin);
    await expect(
      page.getByRole("heading", { level: 2, name: "Dashboard" }),
    ).toBeVisible();
    await expect(
      page.locator('dl[aria-label="Dashboard counts"]'),
    ).toBeVisible();
    await expectNoSeriousAccessibilityViolations(page, "admin dashboard");

    await activateWithKeyboard(
      page.getByRole("button", { name: "Prepare reset" }),
      "Space",
    );
    const resetResponsePromise = page.waitForResponse((response) =>
      responseMatches(response, "POST", "/api/v1/demo/reset"),
    );
    await activateWithKeyboard(
      page.getByRole("button", { name: "Confirm reset" }),
    );
    expect((await resetResponsePromise).status()).toBe(200);
    const resetFeedback = page
      .getByRole("status")
      .filter({ hasText: "Demo reset complete:" });
    await expect(resetFeedback).toBeVisible();
    await expect(resetFeedback).toBeFocused();

    const observer = await newIsolatedPage(browser, baseURL);
    let submittedReadings: readonly { value: number; observedAt: string }[] =
      [];
    try {
      await openAndSignIn(observer.page, seededAccounts.northstarViewer);
      await openProductSection(observer.page, "Alerts");
      await expect(
        observer.page.getByText("Live updates connected", { exact: true }),
      ).toBeVisible();
      await expect(
        observer.page.getByRole("button", {
          name: ALERT_ROW_NAMES.open,
          exact: true,
        }),
      ).toHaveCount(0);

      const scenarioResponsePromise = page.waitForResponse((response) =>
        responseMatches(response, "POST", "/api/v1/telemetry-batches"),
      );
      await activateWithKeyboard(
        page.getByRole("button", { name: "Launch and open alerts" }),
        "Space",
      );
      const scenarioResponse = await scenarioResponsePromise;
      expect(scenarioResponse.status()).toBe(200);
      const scenarioPayload = await readJsonRecord(scenarioResponse);
      expect(scenarioPayload.readingCount).toBe(6);
      expectUuid(scenarioPayload.batchId, "accepted telemetry batch ID");
      submittedReadings = scenarioResponse.request().postDataJSON().readings;
      expect(submittedReadings).toHaveLength(6);
      await expect(
        page.getByRole("button", { name: "Alerts", exact: true }),
      ).toHaveAttribute("aria-current", "page");
      // The already-open observer receives the incident with no navigation or refresh.
      await expect(
        observer.page.getByRole("button", {
          name: ALERT_ROW_NAMES.open,
          exact: true,
        }),
      ).toBeVisible({ timeout: 30_000 });
    } finally {
      try {
        expectCleanBrowser(observer.page);
      } finally {
        await observer.context.close();
      }
    }

    await openProductSection(page, "Assets", "Space");
    await activateWithKeyboard(
      page.getByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );
    await expect(
      page.getByRole("heading", { level: 2, name: "Boiler Feed Pump" }),
    ).toBeVisible();
    const telemetryTable = page.getByRole("table", {
      name: "Recent readings for Bearing Temperature",
    });
    await expect(telemetryTable).toBeVisible();
    await expect(
      page.getByRole("img", {
        name: /Recent readings for Bearing Temperature/i,
      }),
    ).toBeVisible();
    const displayedReadings = await telemetryTable
      .locator("tbody tr")
      .evaluateAll((rows) =>
        rows.map((row) => ({
          observedAt: row.querySelector("time")!.getAttribute("datetime")!,
          value: Number(row.querySelector("data")!.getAttribute("value")),
        })),
      );
    expect(displayedReadings.length).toBeGreaterThanOrEqual(6);
    const readingTimes = displayedReadings.map(({ observedAt }) =>
      Date.parse(observedAt),
    );
    expect(readingTimes.every(Number.isFinite)).toBe(true);
    expect(readingTimes).toEqual(
      [...readingTimes].sort((first, second) => first - second),
    );
    for (const reading of submittedReadings) {
      expect(
        displayedReadings.some(
          (displayed) =>
            Date.parse(displayed.observedAt) ===
              Date.parse(reading.observedAt) &&
            displayed.value === reading.value,
        ),
      ).toBe(true);
    }
    const telemetryRegion = page.getByRole("region", {
      name: "Recent readings for Bearing Temperature",
    });
    await telemetryRegion.focus();
    await expect(telemetryRegion).toBeFocused();
    await expectNoSeriousAccessibilityViolations(
      page,
      "asset telemetry detail",
    );

    await openProductSection(page, "Alerts");
    const openAlertRow = page.getByRole("button", {
      name: ALERT_ROW_NAMES.open,
      exact: true,
    });
    await expect(openAlertRow).toBeVisible({ timeout: 30_000 });
    const alertDetailResponsePromise = page.waitForResponse((response) =>
      responseMatches(response, "GET", ALERT_DETAIL_PATH),
    );
    await activateWithKeyboard(openAlertRow);
    const alertDetailResponse = await alertDetailResponsePromise;
    const alertPayload = await readJsonRecord(alertDetailResponse);
    expectUuid(alertPayload.id, "live alert ID");
    alertId = alertPayload.id;
    await expect(
      page.locator('[aria-labelledby="alert-detail-title"]'),
    ).toBeFocused();
    await expectNoSeriousAccessibilityViolations(page, "live alert detail");

    const acknowledgeResponsePromise = page.waitForResponse((response) =>
      responseMatches(
        response,
        "POST",
        `/api/v1/alerts/${alertId}/acknowledge`,
      ),
    );
    await activateWithKeyboard(
      page.getByRole("button", { name: "Acknowledge alert" }),
      "Space",
    );
    expect((await acknowledgeResponsePromise).status()).toBe(200);
    const acknowledgedFeedback = page
      .getByRole("status")
      .filter({ hasText: "Alert acknowledged." });
    await expect(acknowledgedFeedback).toBeVisible();
    await expect(acknowledgedFeedback).toBeFocused();

    const createResponsePromise = page.waitForResponse((response) =>
      responseMatches(response, "POST", "/api/v1/work-orders"),
    );
    await activateWithKeyboard(
      page.getByRole("button", { name: "Create work order" }),
    );
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const createdWorkOrder = await readJsonRecord(createResponse);
    expectUuid(createdWorkOrder.id, "created work-order ID");
    workOrderId = createdWorkOrder.id;
    expect(createdWorkOrder.status).toBe("OPEN");
    await expect(
      page
        .getByRole("status")
        .filter({ hasText: "Work order created. Open Work orders" }),
    ).toBeVisible();

    await openCurrentWorkOrder(page, WORK_ORDER_ROW_NAMES.open);
    await expect(workOrderFact(page, "Version")).toHaveText("0");
    await expect(
      page.getByLabel("Eligible technician").locator("option:checked"),
    ).toHaveText("Theo Technician");
    await expectNoSeriousAccessibilityViolations(
      page,
      "open work-order detail",
    );

    const assignResponsePromise = page.waitForResponse((response) =>
      responseMatches(
        response,
        "POST",
        `/api/v1/work-orders/${workOrderId}/assign`,
      ),
    );
    await activateWithKeyboard(
      page.getByRole("button", { name: "Assign work order" }),
      "Space",
    );
    const assignResponse = await assignResponsePromise;
    expect(assignResponse.status()).toBe(200);
    const assignedWorkOrder = await readJsonRecord(assignResponse);
    expect(assignedWorkOrder.id).toBe(workOrderId);
    expect(assignedWorkOrder.status).toBe("ASSIGNED");
    expect(assignedWorkOrder.version).toBe(1);
    const assignmentFeedback = page
      .getByRole("status")
      .filter({ hasText: "Work order assigned to Theo Technician." });
    await expect(assignmentFeedback).toBeVisible();
    await expect(assignmentFeedback).toBeFocused();

    await activateWithKeyboard(
      page.getByRole("button", { name: "Back to work orders" }),
    );
    const assignedRow = page.getByRole("button", {
      name: WORK_ORDER_ROW_NAMES.assigned,
      exact: true,
    });
    await expect(assignedRow).toBeVisible();
    await expect(assignedRow).toBeFocused();
  });

  test("isolation and role journey: trusted tenant, role, and ownership boundaries hold", async ({
    browser,
    baseURL,
  }) => {
    const viewer = await newIsolatedPage(browser, baseURL);
    try {
      await openAndSignIn(viewer.page, seededAccounts.northstarViewer);
      await expect(
        viewer.page.getByText("Operations Admin only", { exact: true }),
      ).toBeVisible();
      await expect(
        viewer.page.getByRole("button", { name: "Operations" }),
      ).toHaveCount(0);
      await expect(
        viewer.page.getByRole("button", { name: "Prepare reset" }),
      ).toHaveCount(0);

      await openProductSection(viewer.page, "Alerts");
      await activateWithKeyboard(
        viewer.page.getByRole("button", {
          name: ALERT_ROW_NAMES.acknowledged,
          exact: true,
        }),
      );
      await expect(
        viewer.page.getByRole("button", { name: "Resolve alert" }),
      ).toHaveCount(0);
      await expect(
        viewer.page.getByRole("button", { name: "Create work order" }),
      ).toHaveCount(0);

      await openCurrentWorkOrder(viewer.page, WORK_ORDER_ROW_NAMES.assigned);
      await expect(
        viewer.page.getByText("Read-only", { exact: true }),
      ).toBeVisible();
      await expect(
        viewer.page.getByRole("button", { name: "Assign work order" }),
      ).toHaveCount(0);
      await expect(
        viewer.page.getByRole("button", { name: "Start work" }),
      ).toHaveCount(0);
      await expectNoSeriousAccessibilityViolations(
        viewer.page,
        "viewer work-order detail",
      );

      const viewerCsrf = await getCsrfToken(viewer.page);
      const forbiddenStart = await browserApiRequest(
        viewer.page,
        "POST",
        `/api/v1/work-orders/${workOrderId}/start`,
        {
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json",
            [viewerCsrf.headerName]: viewerCsrf.token,
          },
          data: { expectedVersion: 1 },
        },
      );
      await expectApiProblem(forbiddenStart, "ACCESS_DENIED");
      const forbiddenReset = await browserApiRequest(
        viewer.page,
        "POST",
        "/api/v1/demo/reset",
        {
          headers: {
            Accept: "application/json",
            [viewerCsrf.headerName]: viewerCsrf.token,
          },
        },
      );
      await expectApiProblem(forbiddenReset, "ACCESS_DENIED");

      const foreignAsset = await browserApiRequest(
        viewer.page,
        "GET",
        "/api/v1/assets/20000000-0000-0000-0000-000000000003",
        { headers: { Accept: "application/json" } },
      );
      await expectApiProblem(foreignAsset, "ASSET_NOT_FOUND");

      const spoofedNorthstarAssets = await browserApiRequest(
        viewer.page,
        "GET",
        "/api/v1/assets",
        {
          headers: {
            Accept: "application/json",
            "X-Organisation-Id": "00000000-0000-0000-0000-000000000002",
            "X-Role": "OPERATIONS_ADMIN",
          },
        },
      );
      await expectApiStatus(spoofedNorthstarAssets, 200);
      expect(assetNames(await readJsonRecord(spoofedNorthstarAssets))).toEqual([
        "Boiler Feed Pump",
        "Cooling Water Pump",
      ]);
    } finally {
      try {
        expectCleanBrowser(viewer.page);
      } finally {
        await viewer.context.close();
      }
    }

    const technician = await newIsolatedPage(browser, baseURL);
    try {
      await openAndSignIn(technician.page, seededAccounts.northstarTechnician);
      await expect(
        technician.page.getByText("Operations Admin only", { exact: true }),
      ).toBeVisible();
      await expect(
        technician.page.getByRole("button", { name: "Operations" }),
      ).toHaveCount(0);
      await openCurrentWorkOrder(
        technician.page,
        WORK_ORDER_ROW_NAMES.assigned,
      );
      await expect(
        technician.page.getByRole("button", { name: "Start work" }),
      ).toBeVisible();
      await expect(
        technician.page.getByRole("button", { name: "Assign work order" }),
      ).toHaveCount(0);
      await expectNoSeriousAccessibilityViolations(
        technician.page,
        "technician-owned work-order detail",
      );
    } finally {
      try {
        expectCleanBrowser(technician.page);
      } finally {
        await technician.context.close();
      }
    }

    const riverside = await newIsolatedPage(browser, baseURL);
    try {
      await openAndSignIn(riverside.page, seededAccounts.riversideAdmin);
      await expect(
        riverside.page.getByText("Northstar demo only", { exact: true }),
      ).toBeVisible();
      await openProductSection(riverside.page, "Assets", "Space");
      await expect(
        riverside.page.getByRole("button", {
          name: "View details for Process Pump (PUMP-201)",
        }),
      ).toBeVisible();
      await expect(
        riverside.page.getByText("Boiler Feed Pump", { exact: true }),
      ).toHaveCount(0);
      await expectNoSeriousAccessibilityViolations(
        riverside.page,
        "Riverside asset list",
      );

      const hiddenAlert = await browserApiRequest(
        riverside.page,
        "GET",
        `/api/v1/alerts/${alertId}`,
        { headers: { Accept: "application/json" } },
      );
      await expectApiProblem(hiddenAlert, "ALERT_NOT_FOUND");
      const hiddenWorkOrder = await browserApiRequest(
        riverside.page,
        "GET",
        `/api/v1/work-orders/${workOrderId}`,
        { headers: { Accept: "application/json" } },
      );
      await expectApiProblem(hiddenWorkOrder, "WORK_ORDER_NOT_FOUND");
      const spoofedRiversideAssets = await browserApiRequest(
        riverside.page,
        "GET",
        "/api/v1/assets",
        {
          headers: {
            Accept: "application/json",
            "X-Organisation-Id": "00000000-0000-0000-0000-000000000001",
            "X-Role": "VIEWER",
          },
        },
      );
      await expectApiStatus(spoofedRiversideAssets, 200);
      expect(assetNames(await readJsonRecord(spoofedRiversideAssets))).toEqual([
        "Process Pump",
      ]);
    } finally {
      try {
        expectCleanBrowser(riverside.page);
      } finally {
        await riverside.context.close();
      }
    }
  });

  test("concurrency journey: two technicians produce one winner and one recovered conflict", async ({
    browser,
    baseURL,
  }) => {
    const firstClient = await newIsolatedPage(browser, baseURL);
    const secondClient = await newIsolatedPage(browser, baseURL);
    let conflictClient: Page | undefined;
    const startPath = `/api/v1/work-orders/${workOrderId}/start`;
    try {
      await Promise.all([
        openAndSignIn(firstClient.page, seededAccounts.northstarTechnician),
        openAndSignIn(secondClient.page, seededAccounts.northstarTechnician),
      ]);
      await Promise.all([
        openCurrentWorkOrder(firstClient.page, WORK_ORDER_ROW_NAMES.assigned),
        openCurrentWorkOrder(secondClient.page, WORK_ORDER_ROW_NAMES.assigned),
      ]);
      await Promise.all([
        expect(workOrderFact(firstClient.page, "Version")).toHaveText("1"),
        expect(workOrderFact(secondClient.page, "Version")).toHaveText("1"),
      ]);

      let firstClientStartRequests = 0;
      let secondClientStartRequests = 0;
      firstClient.page.on("request", (request) => {
        if (
          request.method() === "POST" &&
          new URL(request.url()).pathname === startPath
        ) {
          firstClientStartRequests += 1;
        }
      });
      secondClient.page.on("request", (request) => {
        if (
          request.method() === "POST" &&
          new URL(request.url()).pathname === startPath
        ) {
          secondClientStartRequests += 1;
        }
      });

      const firstStartResponsePromise = firstClient.page.waitForResponse(
        (response) => responseMatches(response, "POST", startPath),
      );
      const secondStartResponsePromise = secondClient.page.waitForResponse(
        (response) => responseMatches(response, "POST", startPath),
      );
      await Promise.all([
        activateWithKeyboard(
          firstClient.page.getByRole("button", { name: "Start work" }),
          "Space",
        ),
        activateWithKeyboard(
          secondClient.page.getByRole("button", { name: "Start work" }),
          "Space",
        ),
      ]);
      const startResponses: readonly [Response, Response] = await Promise.all([
        firstStartResponsePromise,
        secondStartResponsePromise,
      ]);
      expect(
        startResponses.map((response) => response.status()).sort(),
      ).toEqual([200, 409]);
      expect(firstClientStartRequests + secondClientStartRequests).toBe(2);

      const winnerIndex = startResponses[0].status() === 200 ? 0 : 1;
      const winner = winnerIndex === 0 ? firstClient.page : secondClient.page;
      const loser = winnerIndex === 0 ? secondClient.page : firstClient.page;
      conflictClient = loser;
      await Promise.all([
        expect(workOrderFact(winner, "Version")).toHaveText("2"),
        expect(workOrderFact(loser, "Version")).toHaveText("2"),
        expect(winner.getByText("In progress", { exact: true })).toBeVisible(),
        expect(loser.getByText("In progress", { exact: true })).toBeVisible(),
      ]);

      const winnerFeedback = winner.getByRole("status").filter({
        hasText: "Work started. The work order is now in progress.",
      });
      await expect(winnerFeedback).toBeVisible();
      await expect(winnerFeedback).toBeFocused();
      const loserFeedback = loser.getByRole("alert").filter({
        hasText:
          "The server accepted another client's change first. The latest saved state is now loaded, and this update was not retried.",
      });
      await expect(loserFeedback).toBeVisible();
      await expect(loserFeedback).toBeFocused();
      await expectNoSeriousAccessibilityViolations(
        loser,
        "recovered concurrency conflict",
      );

      const completePath = `/api/v1/work-orders/${workOrderId}/complete`;
      const completeResponsePromise = winner.waitForResponse((response) =>
        responseMatches(response, "POST", completePath),
      );
      await activateWithKeyboard(
        winner.getByRole("button", { name: "Complete work" }),
      );
      expect((await completeResponsePromise).status()).toBe(200);
      await expect(workOrderFact(winner, "Version")).toHaveText("3");
      await expect(winner.getByText("Done", { exact: true })).toBeVisible();
      const completionFeedback = winner
        .getByRole("status")
        .filter({ hasText: "Work completed. The work order is now done." });
      await expect(completionFeedback).toBeVisible();
      await expect(completionFeedback).toBeFocused();
      const history = winner.getByRole("list", {
        name: "Work-order status history",
      });
      await expect(history.getByRole("listitem")).toHaveCount(3);
      await expect(history.getByRole("listitem").nth(0)).toContainText(
        "Nora Admin",
      );
      await expect(history.getByRole("listitem").nth(1)).toContainText(
        "Theo Technician",
      );
      await expect(history.getByRole("listitem").nth(2)).toContainText(
        "Theo Technician",
      );

      await activateWithKeyboard(
        winner.getByRole("button", { name: "Back to work orders" }),
      );
      const doneRow = winner.getByRole("button", {
        name: WORK_ORDER_ROW_NAMES.done,
        exact: true,
      });
      await expect(doneRow).toBeVisible();
      await expect(doneRow).toBeFocused();
    } finally {
      try {
        expectCleanBrowser(
          firstClient.page,
          conflictClient === firstClient.page ? startPath : undefined,
        );
        expectCleanBrowser(
          secondClient.page,
          conflictClient === secondClient.page ? startPath : undefined,
        );
      } finally {
        await Promise.all([
          firstClient.context.close(),
          secondClient.context.close(),
        ]);
      }
    }

    const administrator = await newIsolatedPage(browser, baseURL);
    try {
      await openAndSignIn(administrator.page, seededAccounts.northstarAdmin);
      const counts = administrator.page.locator(
        'dl[aria-label="Dashboard counts"]',
      );
      await expect(counts.locator("dd")).toHaveText(["2", "0", "0"]);
      const activity = administrator.page.getByRole("region", {
        name: "Recent activity",
      });
      await expect(
        activity
          .getByRole("listitem")
          .filter({ hasText: "Work order completed" }),
      ).toContainText(workOrderId);
      await expectNoSeriousAccessibilityViolations(
        administrator.page,
        "completed incident dashboard",
      );

      await openCurrentWorkOrder(administrator.page, WORK_ORDER_ROW_NAMES.done);
      await expect(workOrderFact(administrator.page, "Version")).toHaveText(
        "3",
      );
      await expect(
        administrator.page
          .getByRole("list", { name: "Work-order status history" })
          .getByRole("listitem"),
      ).toHaveCount(3);
      const savedResponse = await browserApiRequest(
        administrator.page,
        "GET",
        `/api/v1/work-orders/${workOrderId}`,
        { headers: { Accept: "application/json" } },
      );
      await expectApiStatus(savedResponse, 200);
      const saved = await readJsonRecord(savedResponse);
      expect(saved).toMatchObject({
        id: workOrderId,
        alertId,
        status: "DONE",
        version: 3,
      });

      await openProductSection(administrator.page, "Operations");
      const auditTable = administrator.page.getByRole("table", {
        name: "Latest organisation audit events (up to 50)",
      });
      await expect(auditTable).toBeVisible();
      for (const [action, actor, subjectId] of [
        ["Alert acknowledged", "Nora Admin", alertId],
        ["Work order created", "Nora Admin", workOrderId],
        ["Work order assigned", "Nora Admin", workOrderId],
        ["Work order started", "Theo Technician", workOrderId],
        ["Work order completed", "Theo Technician", workOrderId],
      ]) {
        const auditRows = auditTable
          .locator("tbody tr")
          .filter({ hasText: subjectId })
          .filter({ hasText: action });
        await expect(auditRows).toHaveCount(1);
        await expect(auditRows).toContainText(actor);
      }
      const auditRegion = administrator.page.getByRole("region", {
        name: "Latest organisation audit events (up to 50)",
      });
      await auditRegion.focus();
      await expect(auditRegion).toBeFocused();
      await expectNoSeriousAccessibilityViolations(
        administrator.page,
        "completed incident audit trail",
      );
    } finally {
      try {
        expectCleanBrowser(administrator.page);
      } finally {
        await administrator.context.close();
      }
    }

    const cleanup = await resetDemoThroughApi(browser, baseURL);
    expect(cleanup.alertsResolved).toBeGreaterThanOrEqual(1);
    expect(cleanup.workOrdersCompleted).toBe(0);
    cleanupComplete = true;
  });
});
