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

function resetResponse() {
  return jsonResponse({
    resetAt: "2026-08-31T18:00:00Z",
    alertsResolved: 1,
    workOrdersCompleted: 1,
  });
}

function problemResponse(code: string, status: number) {
  return new Response(JSON.stringify({ code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
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

  it.each(["uncertain", "limit-exceeded"])(
    "requires a fresh read and confirmation when reset is %s",
    async (outcome) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(dashboardResponse());
      if (outcome === "uncertain") {
        fetchMock.mockRejectedValueOnce(new TypeError("Connection lost"));
      } else {
        fetchMock.mockResolvedValueOnce(
          problemResponse("DEMO_RESET_LIMIT_EXCEEDED", 409),
        );
      }
      let resolveReview!: (response: Response) => void;
      fetchMock
        .mockReturnValueOnce(
          new Promise<Response>((resolve) => {
            resolveReview = resolve;
          }),
        )
        .mockResolvedValueOnce(resetResponse())
        .mockResolvedValueOnce(dashboardResponse([]));
      vi.stubGlobal("fetch", fetchMock);
      renderDashboard();
      await screen.findByLabelText("Dashboard counts");

      fireEvent.click(screen.getByRole("button", { name: "Prepare reset" }));
      fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
      const feedback = await screen.findByRole("alert");
      expect(feedback).toHaveTextContent(
        outcome === "uncertain" ? "uncertain" : "safety limit",
      );
      fireEvent.click(
        screen.getByRole("button", { name: "Refresh dashboard" }),
      );
      expect(feedback).toBeVisible();
      expect(
        screen.queryByRole("button", { name: "Prepare reset" }),
      ).toBeNull();
      await act(async () => resolveReview(dashboardResponse([])));

      const prepare = await screen.findByRole("button", {
        name: "Prepare reset",
      });
      expect(prepare).toHaveFocus();
      expect(fetchMock).toHaveBeenCalledTimes(3);
      fireEvent.click(prepare);
      expect(fetchMock).toHaveBeenCalledTimes(3);
      fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
      expect(await screen.findByText(/Demo reset complete/)).toBeVisible();
      expect(
        fetchMock.mock.calls.filter(([url]) => url === "/api/v1/demo/reset"),
      ).toHaveLength(2);
    },
  );

  it("preserves success feedback until an explicit refresh prepares another reset", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(dashboardResponse())
      .mockResolvedValueOnce(resetResponse())
      .mockResolvedValueOnce(dashboardResponse([]))
      .mockResolvedValueOnce(dashboardResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard();
    await screen.findByLabelText("Dashboard counts");
    fireEvent.click(screen.getByRole("button", { name: "Prepare reset" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
    const feedback = await screen.findByText(/Demo reset complete/);
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Refresh dashboard" }),
      ).toBeEnabled(),
    );
    expect(feedback).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Prepare reset" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Prepare reset" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Prepare reset" })).toHaveFocus();
    expect(
      fetchMock.mock.calls.filter(([url]) => url === "/api/v1/demo/reset"),
    ).toHaveLength(1);
  });

  it.each(["failure", "timeout"])(
    "keeps an uncertain reset blocked after a dashboard refresh %s",
    async (outcome) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(dashboardResponse())
        .mockRejectedValueOnce(new TypeError("Connection lost"));
      if (outcome === "failure") {
        fetchMock.mockResolvedValueOnce(jsonResponse({}, 503));
      } else {
        fetchMock.mockImplementationOnce(
          (_input, init) =>
            new Promise<Response>((_resolve, reject) => {
              init?.signal?.addEventListener("abort", () => {
                reject(new DOMException("Aborted", "AbortError"));
              });
            }),
        );
      }
      fetchMock.mockResolvedValueOnce(dashboardResponse([]));
      vi.stubGlobal("fetch", fetchMock);
      renderDashboard();
      await screen.findByLabelText("Dashboard counts");
      fireEvent.click(screen.getByRole("button", { name: "Prepare reset" }));
      fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
      const feedback = await screen.findByText(/The reset result is uncertain/);

      if (outcome === "timeout") vi.useFakeTimers();
      fireEvent.click(
        screen.getByRole("button", { name: "Refresh dashboard" }),
      );
      if (outcome === "timeout") {
        await act(async () => vi.advanceTimersByTimeAsync(5_000));
        vi.useRealTimers();
      }
      expect(await screen.findByText(/Refresh failed/)).toBeVisible();
      expect(feedback).toBeVisible();
      expect(
        screen.queryByRole("button", { name: "Prepare reset" }),
      ).toBeNull();

      fireEvent.click(
        screen.getByRole("button", { name: "Refresh dashboard" }),
      );
      expect(
        await screen.findByRole("button", { name: "Prepare reset" }),
      ).toBeEnabled();
      expect(
        fetchMock.mock.calls.filter(([url]) => url === "/api/v1/demo/reset"),
      ).toHaveLength(1);
    },
  );

  it("does not use a refresh started before the reset outcome to unlock another attempt", async () => {
    let resolveEarlierRead!: (response: Response) => void;
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(dashboardResponse())
      .mockReturnValueOnce(
        new Promise<Response>((resolve) => {
          resolveEarlierRead = resolve;
        }),
      )
      .mockRejectedValueOnce(new TypeError("Connection lost"))
      .mockResolvedValueOnce(dashboardResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard();
    await screen.findByLabelText("Dashboard counts");
    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    fireEvent.click(screen.getByRole("button", { name: "Prepare reset" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
    const feedback = await screen.findByText(/The reset result is uncertain/);

    await act(async () => resolveEarlierRead(dashboardResponse([])));
    expect(feedback).toBeVisible();
    expect(screen.queryByRole("button", { name: "Prepare reset" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    expect(
      await screen.findByRole("button", { name: "Prepare reset" }),
    ).toBeEnabled();
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("does not clear a denied reset when a dashboard read is permitted", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(dashboardResponse())
      .mockResolvedValueOnce(problemResponse("ACCESS_DENIED", 403))
      .mockResolvedValueOnce(dashboardResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    renderDashboard();
    await screen.findByLabelText("Dashboard counts");
    fireEvent.click(screen.getByRole("button", { name: "Prepare reset" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm reset" }));
    const feedback = await screen.findByText(
      /The server denied this demo reset/,
    );
    fireEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    await waitFor(() =>
      expect(
        screen.getByRole("button", { name: "Refresh dashboard" }),
      ).toBeEnabled(),
    );
    expect(feedback).toBeVisible();
    expect(screen.queryByRole("button", { name: "Prepare reset" })).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(3);
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
