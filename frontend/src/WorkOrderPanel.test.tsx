import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { WorkOrderPanel } from "./WorkOrderPanel";

const workOrderId = "60000000-0000-0000-0000-000000000001";
const alertId = "50000000-0000-0000-0000-000000000001";
const technicianId = "10000000-0000-0000-0000-000000000002";
const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
} as const;

const adminIdentity = {
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

const openWorkOrder = {
  id: workOrderId,
  alertId,
  status: "OPEN",
  version: 0,
  assignedTechnician: null,
  createdAt: "2026-08-26T08:00:00Z",
  updatedAt: "2026-08-26T08:00:00Z",
  context: {
    assetId: "20000000-0000-0000-0000-000000000001",
    assetCode: "PUMP-101",
    assetName: "Boiler Feed Pump",
    ruleName: "High bearing temperature",
  },
} as const;

const assignedWorkOrder = {
  ...openWorkOrder,
  status: "ASSIGNED",
  version: 1,
  assignedTechnician: {
    id: technicianId,
    displayName: "Theo Technician",
  },
  updatedAt: "2026-08-26T08:01:00Z",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function problemResponse(code: string, status: number) {
  return new Response(JSON.stringify({ code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

function installFetch(
  handler: (url: string, init: RequestInit | undefined) => Promise<Response>,
) {
  const fetchMock = vi.fn<typeof fetch>((input, init) =>
    handler(String(input), init),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("work-order experience", () => {
  it("lists work orders and lets an admin assign with the displayed version", async () => {
    let current = openWorkOrder as
      typeof openWorkOrder | typeof assignedWorkOrder;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [current], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        return jsonResponse(current);
      }
      if (url === "/api/v1/work-orders/eligible-technicians") {
        return jsonResponse({
          technicians: [assignedWorkOrder.assignedTechnician],
        });
      }
      if (url === `/api/v1/work-orders/${workOrderId}/assign`) {
        expect(init?.body).toBe(
          JSON.stringify({
            technicianUserId: technicianId,
            expectedVersion: 0,
          }),
        );
        current = assignedWorkOrder;
        return jsonResponse(assignedWorkOrder);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    render(
      <WorkOrderPanel
        identity={adminIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );

    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open work order for boiler feed pump/i,
      }),
    );
    expect(await screen.findByLabelText("Eligible technician")).toHaveValue(
      technicianId,
    );
    fireEvent.click(screen.getByRole("button", { name: "Assign work order" }));

    expect(
      await screen.findByText("Work order assigned to Theo Technician."),
    ).toBeVisible();
    expect(screen.getByText("Assigned")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Assign work order" }),
    ).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/work-orders/${workOrderId}/assign`,
      expect.objectContaining({
        method: "POST",
        credentials: "same-origin",
        headers: expect.objectContaining({
          "X-CSRF-TOKEN": "csrf-token-value",
        }),
      }),
    );
  });

  it("keeps viewer and technician experiences explicitly read-only", async () => {
    const viewerIdentity = {
      ...adminIdentity,
      role: { code: "VIEWER", displayName: "Viewer" },
    } as const;
    installFetch(async (url) =>
      url === "/api/v1/work-orders?limit=50"
        ? jsonResponse({ workOrders: [openWorkOrder], limit: 50 })
        : jsonResponse(openWorkOrder),
    );

    render(
      <WorkOrderPanel
        identity={viewerIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view open work order/i }),
    );
    expect(await screen.findByText("Read-only")).toBeVisible();
    expect(screen.queryByText("Assign technician")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /assign work order/i }),
    ).not.toBeInTheDocument();
  });

  it("states that an empty technician queue is server-filtered to ownership", async () => {
    const technicianIdentity = {
      ...adminIdentity,
      userId: technicianId,
      role: { code: "TECHNICIAN", displayName: "Technician" },
    } as const;
    installFetch(async () => jsonResponse({ workOrders: [], limit: 50 }));

    render(
      <WorkOrderPanel
        identity={technicianIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    expect(
      await screen.findByText("No work orders are assigned to you."),
    ).toBeVisible();
    expect(
      screen.getByText(/Only work orders assigned to your trusted session/i),
    ).toBeVisible();
  });

  it("recovers an assignment conflict with an authoritative detail read", async () => {
    let detailReads = 0;
    let listValue: typeof openWorkOrder | typeof assignedWorkOrder =
      openWorkOrder;
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [listValue], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        detailReads += 1;
        if (detailReads > 1) {
          listValue = assignedWorkOrder;
          return jsonResponse(assignedWorkOrder);
        }
        return jsonResponse(openWorkOrder);
      }
      if (url === "/api/v1/work-orders/eligible-technicians") {
        return jsonResponse({
          technicians: [assignedWorkOrder.assignedTechnician],
        });
      }
      if (url.endsWith("/assign")) {
        return problemResponse("WORK_ORDER_STATE_CONFLICT", 409);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    render(
      <WorkOrderPanel
        identity={adminIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view open work order/i }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Assign work order" }),
    );

    expect(
      await screen.findByText(/latest saved state is now loaded/i),
    ).toBeVisible();
    expect(screen.getByText("Assigned")).toBeVisible();
    expect(detailReads).toBe(2);
  });

  it("keeps back navigation available when the assignment target disappears", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [openWorkOrder], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        return jsonResponse(openWorkOrder);
      }
      if (url === "/api/v1/work-orders/eligible-technicians") {
        return jsonResponse({
          technicians: [assignedWorkOrder.assignedTechnician],
        });
      }
      if (url.endsWith("/assign")) {
        return problemResponse("WORK_ORDER_NOT_FOUND", 404);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    render(
      <WorkOrderPanel
        identity={adminIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view open work order/i }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Assign work order" }),
    );

    expect(await screen.findByText("Work order not found")).toBeVisible();
    const backButton = screen.getByRole("button", {
      name: "Back to work orders",
    });
    expect(backButton).toBeEnabled();
    fireEvent.click(backButton);
    expect(
      await screen.findByRole("heading", { name: "Work orders" }),
    ).toBeVisible();
  });

  it("keeps back navigation available after terminal conflict recovery", async () => {
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [openWorkOrder], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        detailReads += 1;
        return detailReads === 1
          ? jsonResponse(openWorkOrder)
          : problemResponse("WORK_ORDER_NOT_FOUND", 404);
      }
      if (url === "/api/v1/work-orders/eligible-technicians") {
        return jsonResponse({
          technicians: [assignedWorkOrder.assignedTechnician],
        });
      }
      if (url.endsWith("/assign")) {
        return problemResponse("WORK_ORDER_STATE_CONFLICT", 409);
      }
      throw new Error(`Unexpected URL ${url}`);
    });

    render(
      <WorkOrderPanel
        identity={adminIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view open work order/i }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Assign work order" }),
    );

    expect(await screen.findByText("Work order not found")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Back to work orders" }),
    ).toBeEnabled();
  });

  it("surfaces unavailable/retry and expires a rejected session", async () => {
    let calls = 0;
    const onSessionExpired = vi.fn();
    installFetch(async () => {
      calls += 1;
      return calls === 1
        ? jsonResponse({}, 500)
        : calls === 2
          ? jsonResponse({ workOrders: [], limit: 50 })
          : jsonResponse({}, 401);
    });

    render(
      <WorkOrderPanel
        identity={adminIdentity}
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
      />,
    );
    expect(
      await screen.findByText(/could not load work orders/i),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Retry work orders" }));
    expect(
      await screen.findByText(
        /No work orders are available for this organisation/i,
      ),
    ).toBeVisible();
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh work orders" }),
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
  });
});
