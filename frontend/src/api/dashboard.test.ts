import { describe, expect, it, vi } from "vitest";

import {
  DashboardForbiddenError,
  DashboardSessionExpiredError,
  getDashboard,
} from "./dashboard";

const activity = {
  action: "WORK_ORDER_STARTED",
  subjectType: "WORK_ORDER",
  subjectId: "60000000-0000-0000-0000-000000000001",
  occurredAt: "2026-08-31T18:00:00Z",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("dashboard API client", () => {
  it("accepts bounded counts and newest-first safe activity", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({
        assetCount: 2,
        openAlertCount: 1,
        activeWorkOrderCount: 1,
        recentActivity: [
          activity,
          {
            ...activity,
            action: "WORK_ORDER_ASSIGNED",
            occurredAt: "2026-08-31T17:59:00Z",
          },
        ],
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const summary = await getDashboard();
    expect(summary.assetCount).toBe(2);
    expect(summary.recentActivity.map(({ action }) => action)).toEqual([
      "WORK_ORDER_STARTED",
      "WORK_ORDER_ASSIGNED",
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/dashboard",
      expect.objectContaining({
        method: "GET",
        credentials: "same-origin",
        cache: "no-store",
      }),
    );
  });

  it("accepts indistinguishable bounded activity when audit IDs are omitted", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          assetCount: 1,
          openAlertCount: 0,
          activeWorkOrderCount: 1,
          recentActivity: [activity, activity],
        }),
      ),
    );

    await expect(getDashboard()).resolves.toMatchObject({
      recentActivity: [activity, activity],
    });
  });

  it.each([
    [{ assetCount: -1 }, "unexpected payload"],
    [
      {
        assetCount: 1,
        openAlertCount: 0,
        activeWorkOrderCount: 0,
        recentActivity: [{ ...activity, actor: "private" }],
      },
      "unexpected payload",
    ],
    [
      {
        assetCount: 1,
        openAlertCount: 0,
        activeWorkOrderCount: 0,
        recentActivity: [
          { ...activity, occurredAt: "2026-08-31T17:59:00Z" },
          activity,
        ],
      },
      "unexpected payload",
    ],
  ])("rejects malformed or overexposed payloads", async (payload, message) => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
    );
    await expect(getDashboard()).rejects.toThrow(message);
  });

  it("classifies authentication and authorization failures", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 401))
      .mockResolvedValueOnce(jsonResponse({}, 403));
    vi.stubGlobal("fetch", fetchMock);
    await expect(getDashboard()).rejects.toBeInstanceOf(
      DashboardSessionExpiredError,
    );
    await expect(getDashboard()).rejects.toBeInstanceOf(
      DashboardForbiddenError,
    );
  });
});
