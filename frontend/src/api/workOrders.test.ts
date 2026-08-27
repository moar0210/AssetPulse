import { describe, expect, it, vi } from "vitest";

import {
  assignWorkOrder,
  createWorkOrder,
  getEligibleTechnicians,
  getWorkOrderDetail,
  getWorkOrders,
  InvalidWorkOrderAssigneeError,
  WorkOrderAlreadyExistsError,
  WorkOrderCommandUncertainError,
  WorkOrderForbiddenError,
  WorkOrderNotFoundError,
  WorkOrderRequestVerificationError,
  WorkOrderSessionExpiredError,
  WorkOrderSourceAlertNotFoundError,
  WorkOrderStateConflictError,
} from "./workOrders";

const workOrderId = "60000000-0000-0000-0000-000000000001";
const alertId = "50000000-0000-0000-0000-000000000001";
const technicianId = "10000000-0000-0000-0000-000000000002";
const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
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
  return new Response(
    JSON.stringify({
      type: `urn:assetpulse:problem:${code.toLowerCase()}`,
      title: "Request rejected",
      status,
      detail: "The request was rejected.",
      instance: "/api/v1/work-orders",
      code,
      correlationId: "correlation-id",
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

describe("work-order API client", () => {
  it("loads an exact bounded work-order queue", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        jsonResponse({ workOrders: [openWorkOrder], limit: 50 }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getWorkOrders()).resolves.toEqual({
      workOrders: [openWorkOrder],
      limit: 50,
    });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/work-orders?limit=50", {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
      signal: undefined,
    });
  });

  it("rejects extra fields, duplicate ids, and incoherent work orders", async () => {
    const malformedPayloads = [
      { workOrders: [openWorkOrder], limit: 50, organisationId: "secret" },
      { workOrders: [openWorkOrder, openWorkOrder], limit: 50 },
      {
        workOrders: [
          {
            ...openWorkOrder,
            assignedTechnician: assignedWorkOrder.assignedTechnician,
          },
        ],
        limit: 50,
      },
      {
        workOrders: [{ ...assignedWorkOrder, version: 0 }],
        limit: 50,
      },
      {
        workOrders: [{ ...openWorkOrder, updatedAt: "2026-08-26T07:59:59Z" }],
        limit: 50,
      },
      {
        workOrders: [
          {
            ...openWorkOrder,
            context: { ...openWorkOrder.context, assetCode: "pump 101" },
          },
        ],
        limit: 50,
      },
    ];

    for (const payload of malformedPayloads) {
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
      );
      await expect(getWorkOrders()).rejects.toThrow("unexpected payload");
    }
  });

  it("loads matching detail and classifies protected read failures", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse(openWorkOrder));
    await expect(getWorkOrderDetail(workOrderId)).resolves.toEqual(
      openWorkOrder,
    );

    fetchMock.mockResolvedValueOnce(
      jsonResponse({ ...openWorkOrder, id: technicianId }),
    );
    await expect(getWorkOrderDetail(workOrderId)).rejects.toThrow(
      "unexpected payload",
    );

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(getWorkOrders()).rejects.toBeInstanceOf(
      WorkOrderSessionExpiredError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
    await expect(getWorkOrders()).rejects.toBeInstanceOf(
      WorkOrderForbiddenError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 404));
    await expect(getWorkOrderDetail(workOrderId)).rejects.toBeInstanceOf(
      WorkOrderNotFoundError,
    );
  });

  it("loads an exact, duplicate-free eligible-technician list", async () => {
    const technician = assignedWorkOrder.assignedTechnician!;
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ technicians: [technician] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getEligibleTechnicians()).resolves.toEqual({
      technicians: [technician],
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/work-orders/eligible-technicians",
      {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        signal: undefined,
      },
    );

    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse({ technicians: [technician, technician] }),
        ),
    );
    await expect(getEligibleTechnicians()).rejects.toThrow(
      "unexpected payload",
    );
  });

  it("creates with the exact JSON body, CSRF header, and 201 response", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(openWorkOrder, 201));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createWorkOrder(alertId, csrfToken)).resolves.toEqual(
      openWorkOrder,
    );
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/work-orders", {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf-token-value",
      },
      credentials: "same-origin",
      body: JSON.stringify({ alertId }),
      signal: undefined,
    });
  });

  it("classifies stable create problems and uncertain create outcomes", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderSessionExpiredError,
    );
    fetchMock.mockResolvedValueOnce(problemResponse("CSRF_REJECTED", 403));
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderRequestVerificationError,
    );
    fetchMock.mockResolvedValueOnce(problemResponse("ACCESS_DENIED", 403));
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderForbiddenError,
    );
    fetchMock.mockResolvedValueOnce(
      problemResponse("WORK_ORDER_SOURCE_ALERT_NOT_FOUND", 404),
    );
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderSourceAlertNotFoundError,
    );
    fetchMock.mockResolvedValueOnce(
      problemResponse("WORK_ORDER_ALREADY_EXISTS", 409),
    );
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderAlreadyExistsError,
    );

    fetchMock.mockRejectedValueOnce(new TypeError("connection lost"));
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderCommandUncertainError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({ id: workOrderId }, 201));
    await expect(createWorkOrder(alertId, csrfToken)).rejects.toBeInstanceOf(
      WorkOrderCommandUncertainError,
    );
  });

  it("assigns with expectedVersion and validates the authoritative result", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(assignedWorkOrder));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).resolves.toEqual(assignedWorkOrder);
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/work-orders/${workOrderId}/assign`,
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": "csrf-token-value",
        },
        credentials: "same-origin",
        body: JSON.stringify({
          technicianUserId: technicianId,
          expectedVersion: 0,
        }),
        signal: undefined,
      },
    );
  });

  it("classifies assignment denials, conflict, invalid assignee, and uncertainty", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(
      problemResponse("INVALID_WORK_ORDER_ASSIGNEE", 400),
    );
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(InvalidWorkOrderAssigneeError);
    fetchMock.mockResolvedValueOnce(
      problemResponse("WORK_ORDER_NOT_FOUND", 404),
    );
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderNotFoundError);
    fetchMock.mockResolvedValueOnce(
      problemResponse("WORK_ORDER_STATE_CONFLICT", 409),
    );
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderStateConflictError);
    fetchMock.mockRejectedValueOnce(new TypeError("connection lost"));
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
    fetchMock.mockResolvedValueOnce(jsonResponse(openWorkOrder));
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
  });
});
