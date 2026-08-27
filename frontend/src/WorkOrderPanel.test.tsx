import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { WorkOrderPanel } from "./WorkOrderPanel";
import type { WorkOrder, WorkOrderDetail } from "./api/workOrders";
import type { SessionIdentity } from "./api/session";

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

const technicianIdentity = {
  ...adminIdentity,
  userId: technicianId,
  displayName: "Theo Technician",
  email: "technician@northstar.example",
  role: { code: "TECHNICIAN", displayName: "Technician" },
} as const;
const openDetail = { ...openWorkOrder, history: [] } as const;
const assignedDetail = {
  ...assignedWorkOrder,
  history: [
    {
      sequenceNumber: 1,
      fromStatus: "OPEN",
      toStatus: "ASSIGNED",
      actor: {
        id: adminIdentity.userId,
        displayName: adminIdentity.displayName,
      },
      transitionedAt: assignedWorkOrder.updatedAt,
    },
  ],
} as const;
const inProgressDetail = {
  ...assignedDetail,
  status: "IN_PROGRESS",
  version: 2,
  updatedAt: "2026-08-26T08:02:00Z",
  history: [
    ...assignedDetail.history,
    {
      sequenceNumber: 2,
      fromStatus: "ASSIGNED",
      toStatus: "IN_PROGRESS",
      actor: assignedWorkOrder.assignedTechnician,
      transitionedAt: "2026-08-26T08:02:00Z",
    },
  ],
} as const;
const doneDetail = {
  ...inProgressDetail,
  status: "DONE",
  version: 3,
  updatedAt: "2026-08-26T08:03:00Z",
  history: [
    ...inProgressDetail.history,
    {
      sequenceNumber: 3,
      fromStatus: "IN_PROGRESS",
      toStatus: "DONE",
      actor: assignedWorkOrder.assignedTechnician,
      transitionedAt: "2026-08-26T08:03:00Z",
    },
  ],
} as const;

function summaryOf({
  history: _history,
  ...summary
}: WorkOrderDetail): WorkOrder {
  return summary;
}

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

async function openDetailAs(
  identity: SessionIdentity = technicianIdentity,
  onSessionExpired: () => void = () => {},
) {
  render(
    <WorkOrderPanel
      identity={identity}
      csrfToken={csrfToken}
      onSessionExpired={onSessionExpired}
    />,
  );
  fireEvent.click(
    await screen.findByRole("button", {
      name: /view .* work order for boiler feed pump/i,
    }),
  );
  await screen.findByRole("heading", { name: "High bearing temperature" });
}

function pendingResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

describe("work-order experience", () => {
  it("lists work orders and lets an admin assign with the displayed version", async () => {
    let current: WorkOrderDetail = openDetail;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [summaryOf(current)], limit: 50 });
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
        current = assignedDetail;
        return jsonResponse(assignedDetail);
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

  it("keeps the viewer experience explicitly read-only", async () => {
    const viewerIdentity = {
      ...adminIdentity,
      role: { code: "VIEWER", displayName: "Viewer" },
    } as const;
    installFetch(async (url) =>
      url === "/api/v1/work-orders?limit=50"
        ? jsonResponse({ workOrders: [openWorkOrder], limit: 50 })
        : jsonResponse(openDetail),
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
          return jsonResponse(assignedDetail);
        }
        return jsonResponse(openDetail);
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
        return jsonResponse(openDetail);
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
          ? jsonResponse(openDetail)
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

  it("WO-03/WO-04: lets only the owner start then complete work and shows its history", async () => {
    let current: WorkOrderDetail = assignedDetail;
    const pendingStart = pendingResponse();
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [summaryOf(current)], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        return jsonResponse(current);
      }
      if (url.endsWith("/start")) {
        expect(init?.body).toBe(JSON.stringify({ expectedVersion: 1 }));
        return pendingStart.promise;
      }
      if (url.endsWith("/complete")) {
        expect(init?.body).toBe(JSON.stringify({ expectedVersion: 2 }));
        current = doneDetail;
        return jsonResponse(current);
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openDetailAs();

    expect(screen.queryByText("Read-only")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Complete work" }),
    ).not.toBeInTheDocument();
    const startButton = screen.getByRole("button", { name: "Start work" });
    startButton.focus();
    fireEvent.click(startButton);
    fireEvent.click(startButton);
    expect(
      screen.getByRole("button", { name: "Starting work…" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Back to work orders" }),
    ).toBeDisabled();
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(1);

    current = inProgressDetail;
    await act(async () => pendingStart.resolve(jsonResponse(current)));
    const started = await screen.findByText(
      "Work started. The work order is now in progress.",
    );
    expect(started).toHaveFocus();
    expect(
      screen.queryByRole("button", { name: "Start work" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Complete work" }));
    const completed = await screen.findByText(
      "Work completed. The work order is now done.",
    );
    expect(completed).toHaveFocus();
    expect(screen.getByText("Done")).toBeVisible();
    expect(screen.getByText("Read-only")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: /^(start|complete) work$/i }),
    ).not.toBeInTheDocument();

    const history = within(
      screen.getByRole("list", { name: "Work-order status history" }),
    );
    const transitions = history.getAllByRole("listitem");
    expect(transitions).toHaveLength(3);
    expect(transitions[0]).toHaveTextContent("Open → Assigned");
    expect(transitions[0]).toHaveTextContent("Nora Admin");
    expect(transitions[1]).toHaveTextContent("Assigned → In progress");
    expect(transitions[1]).toHaveTextContent("Theo Technician");
    expect(transitions[2]).toHaveTextContent("In progress → Done");
    expect(transitions[2]).toHaveTextContent("Theo Technician");
    expect(
      transitions.map((entry) => entry.querySelector("time")?.dateTime),
    ).toEqual(doneDetail.history.map((entry) => entry.transitionedAt));
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(2);
    for (const [url] of fetchMock.mock.calls.filter(
      ([, init]) => init?.method === "POST",
    )) {
      expect(fetchMock).toHaveBeenCalledWith(
        url,
        expect.objectContaining({
          credentials: "same-origin",
          headers: expect.objectContaining({ "X-CSRF-TOKEN": csrfToken.token }),
        }),
      );
    }
    fireEvent.click(
      screen.getByRole("button", { name: "Back to work orders" }),
    );
    expect(
      await screen.findByRole("button", { name: /view done work order/i }),
    ).toHaveFocus();
  });

  const readOnlyCases = [
    { role: "admin", identity: adminIdentity },
    {
      role: "viewer",
      identity: {
        ...adminIdentity,
        role: { code: "VIEWER", displayName: "Viewer" },
      } as const,
    },
    {
      role: "other technician",
      identity: {
        ...technicianIdentity,
        userId: "10000000-0000-0000-0000-000000000003",
      },
    },
  ].flatMap((viewer) =>
    [assignedDetail, inProgressDetail, doneDetail].map((detail) => ({
      ...viewer,
      detail,
      status: detail.status,
    })),
  );

  it.each(readOnlyCases)(
    "AUTH-03/WO-02: keeps $status technician commands absent for $role",
    async ({ identity, detail }) => {
      const fetchMock = installFetch(async (url) =>
        url === "/api/v1/work-orders?limit=50"
          ? jsonResponse({ workOrders: [summaryOf(detail)], limit: 50 })
          : jsonResponse(detail),
      );
      await openDetailAs(identity);

      expect(screen.getByText("Read-only")).toBeVisible();
      expect(
        screen.getByRole("list", { name: "Work-order status history" }),
      ).toBeVisible();
      expect(
        screen.queryByRole("button", {
          name: /^(start|complete|assign) work/i,
        }),
      ).not.toBeInTheDocument();
      expect(
        fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
      ).toBe(true);
      expect(
        fetchMock.mock.calls.some(([input]) =>
          String(input).endsWith("/eligible-technicians"),
        ),
      ).toBe(false);
    },
  );

  it("WO-04: labels legacy attribution as unknown without inventing an actor", async () => {
    const legacyDetail = {
      ...assignedDetail,
      updatedAt: "2026-08-26T08:01:30Z",
      history: [{ ...assignedDetail.history[0], actor: null }],
    };
    installFetch(async (url) =>
      url === "/api/v1/work-orders?limit=50"
        ? jsonResponse({ workOrders: [summaryOf(legacyDetail)], limit: 50 })
        : jsonResponse(legacyDetail),
    );
    await openDetailAs();

    const history = within(
      screen.getByRole("list", { name: "Work-order status history" }),
    );
    expect(
      history.getByText("Unknown actor (assignment predates history tracking)"),
    ).toBeVisible();
    expect(history.queryByText("Nora Admin")).not.toBeInTheDocument();
    expect(history.queryByText("Theo Technician")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start work" })).toBeEnabled();
  });

  const lifecycleCommands = [
    {
      command: "start",
      actionName: "Start work",
      before: assignedDetail,
      after: inProgressDetail,
    },
    {
      command: "complete",
      actionName: "Complete work",
      before: inProgressDetail,
      after: doneDetail,
    },
  ] as const;

  it.each(lifecycleCommands)(
    "WO-05: locks $command until failed conflict recovery is explicitly retried",
    async ({ command, actionName, before, after }) => {
      let detailReads = 0;
      const recovery = pendingResponse();
      const fetchMock = installFetch(async (url) => {
        if (url === "/api/v1/work-orders?limit=50") {
          return jsonResponse({
            workOrders: [summaryOf(detailReads > 2 ? after : before)],
            limit: 50,
          });
        }
        if (url === `/api/v1/work-orders/${workOrderId}`) {
          detailReads += 1;
          return detailReads === 1
            ? jsonResponse(before)
            : detailReads === 2
              ? recovery.promise
              : jsonResponse(after);
        }
        if (url.endsWith(`/${command}`)) {
          return problemResponse("WORK_ORDER_STATE_CONFLICT", 409);
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      await openDetailAs();
      fireEvent.click(screen.getByRole("button", { name: actionName }));
      expect(
        await screen.findByRole("button", { name: "Recovering latest state…" }),
      ).toBeDisabled();
      expect(
        screen.getByRole("button", { name: "Back to work orders" }),
      ).toBeDisabled();

      await act(async () => recovery.resolve(jsonResponse({}, 503)));
      expect(
        await screen.findByRole("button", { name: "Latest state required" }),
      ).toBeDisabled();
      expect(
        screen.getByText(/Retry latest state before making another change/i),
      ).toHaveFocus();
      fireEvent.click(
        screen.getByRole("button", { name: "Latest state required" }),
      );
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
      expect(detailReads).toBe(2);

      fireEvent.click(
        screen.getByRole("button", { name: "Retry latest state" }),
      );
      expect(
        await screen.findByText(/latest saved state is now loaded/i),
      ).toHaveFocus();
      expect(
        screen.queryByRole("button", { name: actionName }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "Retry latest state" }),
      ).not.toBeInTheDocument();
      expect(detailReads).toBe(3);
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
      expect(
        within(
          screen.getByRole("list", { name: "Work-order status history" }),
        ).getAllByRole("listitem"),
      ).toHaveLength(after.version);
    },
  );

  it.each(lifecycleCommands)(
    "WO-05: recovers a committed $command after a lost response without replay",
    async ({ command, actionName, before, after }) => {
      let current: WorkOrderDetail = before;
      let detailReads = 0;
      const fetchMock = installFetch(async (url) => {
        if (url === "/api/v1/work-orders?limit=50") {
          return jsonResponse({ workOrders: [summaryOf(current)], limit: 50 });
        }
        if (url === `/api/v1/work-orders/${workOrderId}`) {
          detailReads += 1;
          return jsonResponse(current);
        }
        if (url.endsWith(`/${command}`)) {
          current = after;
          throw new TypeError("response lost");
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      await openDetailAs();
      fireEvent.click(screen.getByRole("button", { name: actionName }));

      expect(
        await screen.findByText(
          /update result could not be confirmed. The latest saved state is now loaded/i,
        ),
      ).toBeVisible();
      expect(
        screen.queryByRole("button", { name: actionName }),
      ).not.toBeInTheDocument();
      expect(detailReads).toBe(2);
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
    },
  );

  it("WO-05: permits a new manual attempt only after recovery confirms the unchanged version", async () => {
    let commands = 0;
    let detailReads = 0;
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [assignedWorkOrder], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        detailReads += 1;
        return jsonResponse(assignedDetail);
      }
      if (url.endsWith("/start")) {
        commands += 1;
        if (commands === 1) {
          throw new TypeError("request failed");
        }
        return jsonResponse(inProgressDetail);
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openDetailAs();
    fireEvent.click(screen.getByRole("button", { name: "Start work" }));

    expect(
      await screen.findByText(/latest saved state is now loaded/i),
    ).toBeVisible();
    expect(commands).toBe(1);
    expect(detailReads).toBe(2);
    fireEvent.click(screen.getByRole("button", { name: "Start work" }));
    expect(await screen.findByText(/Work started/i)).toBeVisible();
    expect(commands).toBe(2);
    const posts = fetchMock.mock.calls.filter(
      ([, init]) => init?.method === "POST",
    );
    expect(posts.map(([, init]) => init?.body)).toEqual([
      JSON.stringify({ expectedVersion: 1 }),
      JSON.stringify({ expectedVersion: 1 }),
    ]);
  });

  it.each(
    lifecycleCommands.flatMap((command) => [
      { ...command, status: 401, code: "AUTHENTICATION_REQUIRED" },
      { ...command, status: 403, code: "CSRF_REJECTED" },
    ]),
  )(
    "AUTH-04: expires the session on $command $code without recovery or replay",
    async ({ command, actionName, before, status, code }) => {
      const onSessionExpired = vi.fn();
      let detailReads = 0;
      const fetchMock = installFetch(async (url) => {
        if (url === "/api/v1/work-orders?limit=50") {
          return jsonResponse({ workOrders: [summaryOf(before)], limit: 50 });
        }
        if (url === `/api/v1/work-orders/${workOrderId}`) {
          detailReads += 1;
          return jsonResponse(before);
        }
        if (url.endsWith(`/${command}`)) {
          return problemResponse(code, status);
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      await openDetailAs(technicianIdentity, onSessionExpired);
      fireEvent.click(screen.getByRole("button", { name: actionName }));

      await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
      expect(detailReads).toBe(1);
      expect(
        screen.getByRole("button", {
          name: command === "start" ? "Starting work…" : "Completing work…",
        }),
      ).toBeDisabled();
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
    },
  );

  it.each([
    { status: 403, code: "ACCESS_DENIED", heading: "Work order access denied" },
    {
      status: 404,
      code: "WORK_ORDER_NOT_FOUND",
      heading: "Work order not found",
    },
  ])(
    "AUTH-03: removes owned controls on authoritative $status denial",
    async ({ status, code, heading }) => {
      installFetch(async (url) => {
        if (url === "/api/v1/work-orders?limit=50") {
          return jsonResponse({ workOrders: [assignedWorkOrder], limit: 50 });
        }
        return url.endsWith("/start")
          ? problemResponse(code, status)
          : jsonResponse(assignedDetail);
      });
      await openDetailAs();
      fireEvent.click(screen.getByRole("button", { name: "Start work" }));

      expect(
        await screen.findByRole("heading", { name: heading }),
      ).toBeVisible();
      expect(
        screen.queryByRole("button", { name: /^(start|complete) work$/i }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Back to work orders" }),
      ).toBeEnabled();
      expect(screen.getByRole("region", { name: heading })).toHaveFocus();
    },
  );

  it("AUTH-04/WO-05: does not unlock a command when its recovery read finds an expired session", async () => {
    const onSessionExpired = vi.fn();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [assignedWorkOrder], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        detailReads += 1;
        return detailReads === 1
          ? jsonResponse(assignedDetail)
          : problemResponse("AUTHENTICATION_REQUIRED", 401);
      }
      return problemResponse("WORK_ORDER_STATE_CONFLICT", 409);
    });
    await openDetailAs(technicianIdentity, onSessionExpired);
    fireEvent.click(screen.getByRole("button", { name: "Start work" }));

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();
    expect(detailReads).toBe(2);
  });

  it("WO-04: keeps malformed detail unavailable and offers a protected retry", async () => {
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [assignedWorkOrder], limit: 50 });
      }
      detailReads += 1;
      return jsonResponse(
        detailReads === 1 ? { ...assignedDetail, history: [] } : assignedDetail,
      );
    });
    render(
      <WorkOrderPanel
        identity={technicianIdentity}
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view assigned work order/i }),
    );
    expect(
      await screen.findByRole("heading", { name: "Work order unavailable" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Start work" }),
    ).not.toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: "Retry work order details" }),
    );
    expect(
      await screen.findByRole("button", { name: "Start work" }),
    ).toBeEnabled();
    expect(detailReads).toBe(2);
  });

  it("WO-05: recovers an aborted command at the action timeout without retrying it", async () => {
    let current: WorkOrderDetail = assignedDetail;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [summaryOf(current)], limit: 50 });
      }
      if (url === `/api/v1/work-orders/${workOrderId}`) {
        return jsonResponse(current);
      }
      if (url.endsWith("/start")) {
        return new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => {
              current = inProgressDetail;
              reject(new DOMException("Aborted", "AbortError"));
            },
            { once: true },
          );
        });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openDetailAs();
    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole("button", { name: "Start work" }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_000);
      });
    } finally {
      vi.useRealTimers();
    }

    expect(
      await screen.findByText(/latest saved state is now loaded/i),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Start work" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Complete work" })).toBeEnabled();
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(1);
  });
});
