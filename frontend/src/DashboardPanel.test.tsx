import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { DashboardPanel } from "./DashboardPanel";

const identity = {
  userId: "10000000-0000-0000-0000-000000000001",
  displayName: "Nora Admin",
  email: "admin@northstar.example",
  organisation: {
    id: "00000000-0000-0000-0000-000000000001",
    slug: "northstar-operations",
    name: "Northstar Operations",
  },
  role: { code: "OPERATIONS_ADMIN", displayName: "Operations Admin" },
} as const;
const csrfToken = { headerName: "X-CSRF-TOKEN", token: "csrf-token" } as const;
const activity = {
  action: "ALERT_ACKNOWLEDGED",
  subjectType: "ALERT",
  subjectId: "50000000-0000-0000-0000-000000000001",
  occurredAt: "2026-08-31T18:00:00Z",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function dashboardResponse(recentActivity: readonly unknown[] = [activity]) {
  return jsonResponse({
    assetCount: 2,
    openAlertCount: 1,
    activeWorkOrderCount: 1,
    recentActivity,
  });
}

function renderDashboard(
  overrides: Partial<Parameters<typeof DashboardPanel>[0]> = {},
) {
  const props = {
    identity,
    csrfToken,
    onSessionExpired: vi.fn(),
    onOpenAlerts: vi.fn(),
    ...overrides,
  };
  render(<DashboardPanel {...props} />);
  return props;
}

describe("integrated dashboard", () => {
  it("announces loading, shows the three counts, and renders safe recent activity", async () => {
    let resolve!: (response: Response) => void;
    const pending = new Promise<Response>((resolvePromise) => {
      resolve = resolvePromise;
    });
    vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockReturnValue(pending));
    renderDashboard();
    expect(screen.getByRole("status")).toHaveTextContent("Loading dashboard");
    await act(async () => resolve(dashboardResponse()));

    const counts = screen.getByLabelText("Dashboard counts");
    expect(within(counts).getByText("2", { selector: "dd" })).toBeVisible();
    expect(screen.getByText("Alert acknowledged")).toBeVisible();
    expect(screen.getByText(activity.subjectId)).toBeVisible();
    expect(screen.queryByText(/correlation/i)).toBeNull();
    expect(
      screen.getByRole("button", { name: "Launch and open alerts" }),
    ).toBeEnabled();
    expect(screen.getByText("2", { selector: "dd" })).toBeVisible();
  });

  it("shows explicit empty, unavailable/retry, and server-forbidden states", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 503))
      .mockResolvedValueOnce(dashboardResponse([]))
      .mockResolvedValueOnce(jsonResponse({}, 403));
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load",
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry dashboard" }));
    expect(await screen.findByText(/No workflow activity/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    expect(
      await screen.findByRole("heading", { name: "Dashboard access denied" }),
    ).toBeVisible();
    expect(screen.queryByText(/No workflow activity/)).toBeNull();
  });

  it("delegates session expiry and clears trusted dashboard data", async () => {
    const onSessionExpired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 401)),
    );
    renderDashboard({ onSessionExpired });
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
    expect(screen.getByRole("status")).toHaveTextContent("session expired");
    expect(screen.queryByRole("definition")).toBeNull();
  });

  it("launches the public-API scenario then opens the live alert queue", async () => {
    const onOpenAlerts = vi.fn();
    const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
      if (String(input) === "/api/v1/dashboard") return dashboardResponse();
      const body = JSON.parse(String(init?.body)) as { idempotencyKey: string };
      return jsonResponse({
        batchId: "70000000-0000-0000-0000-000000000001",
        idempotencyKey: body.idempotencyKey,
        readingCount: 6,
        acceptedAt: "2026-08-31T18:00:00Z",
      });
    });
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard({ onOpenAlerts });
    fireEvent.click(
      await screen.findByRole("button", { name: "Launch and open alerts" }),
    );
    await waitFor(() => expect(onOpenAlerts).toHaveBeenCalledOnce());
    const command = fetchMock.mock.calls.find(
      ([url]) => url === "/api/v1/telemetry-batches",
    );
    expect(command?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token" }),
    );
  });

  it("requires explicit confirmation, resets, focuses feedback, and refreshes counts", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(dashboardResponse())
      .mockResolvedValueOnce(
        jsonResponse({
          resetAt: "2026-08-31T18:00:00Z",
          alertsResolved: 1,
          workOrdersCompleted: 1,
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          assetCount: 2,
          openAlertCount: 0,
          activeWorkOrderCount: 0,
          recentActivity: [],
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard();
    fireEvent.click(
      await screen.findByRole("button", { name: "Prepare reset" }),
    );
    expect(screen.getByText(/advances active workflow/)).toBeVisible();
    const confirm = screen.getByRole("button", { name: "Confirm reset" });
    await waitFor(() => expect(confirm).toHaveFocus());
    fireEvent.click(confirm);
    const feedback = await screen.findByText(/Demo reset complete/);
    await waitFor(() => expect(feedback).toHaveFocus());
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3));
    expect(screen.getAllByText("0", { selector: "dd" })).toHaveLength(2);
  });

  it("moves keyboard focus into and back out of reset confirmation", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(dashboardResponse([])),
    );
    renderDashboard();

    const prepare = await screen.findByRole("button", {
      name: "Prepare reset",
    });
    fireEvent.click(prepare);
    const confirm = screen.getByRole("button", { name: "Confirm reset" });
    await waitFor(() => expect(confirm).toHaveFocus());

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Prepare reset" }),
      ).toHaveFocus(),
    );
  });

  it("keeps viewer controls read-only while retaining dashboard data", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(dashboardResponse([])),
    );
    renderDashboard({
      identity: {
        ...identity,
        role: { code: "VIEWER", displayName: "Viewer" },
      },
    });
    expect(await screen.findByText("Operations Admin only")).toBeVisible();
    expect(screen.queryByRole("button", { name: /reset/i })).toBeNull();
    expect(
      screen.queryByRole("button", { name: /Launch and open/i }),
    ).toBeNull();
  });
});
