import { describe, expect, it, vi } from "vitest";

import {
  assignWorkOrder,
  completeWorkOrder,
  createWorkOrder,
  getEligibleTechnicians,
  getWorkOrderDetail,
  getWorkOrders,
  InvalidWorkOrderAssigneeError,
  startWorkOrder,
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

const openDetail = { ...openWorkOrder, history: [] } as const;
const assignedDetail = {
  ...assignedWorkOrder,
  history: [
    {
      sequenceNumber: 1,
      fromStatus: "OPEN",
      toStatus: "ASSIGNED",
      actor: {
        id: "10000000-0000-0000-0000-000000000001",
        displayName: "Nora Admin",
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

    fetchMock.mockResolvedValueOnce(jsonResponse(openDetail));
    await expect(getWorkOrderDetail(workOrderId)).resolves.toEqual(openDetail);

    fetchMock.mockResolvedValueOnce(
      jsonResponse({ ...openDetail, id: technicianId }),
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
      .mockResolvedValue(jsonResponse(openDetail, 201));
    vi.stubGlobal("fetch", fetchMock);

    await expect(createWorkOrder(alertId, csrfToken)).resolves.toEqual(
      openDetail,
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
      .mockResolvedValue(jsonResponse(assignedDetail));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).resolves.toEqual(assignedDetail);
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
    fetchMock.mockResolvedValueOnce(jsonResponse(openDetail));
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
  });

  it("WO-03: keeps lifecycle queue entries as exact summaries", async () => {
    const workOrders = [
      openDetail,
      assignedDetail,
      inProgressDetail,
      doneDetail,
    ].map(({ history: _history, ...summary }, index) => ({
      ...summary,
      id: `60000000-0000-0000-0000-00000000000${index + 1}`,
    }));
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ workOrders, limit: 50 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getWorkOrders()).resolves.toEqual({ workOrders, limit: 50 });
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ workOrders: [assignedDetail], limit: 50 }),
    );
    await expect(getWorkOrders()).rejects.toThrow("unexpected payload");
    for (const invalid of [
      { ...workOrders[2], version: 1 },
      { ...workOrders[2], assignedTechnician: null },
      { ...workOrders[3], version: 4 },
      { ...workOrders[3], version: 2.5 },
      { ...workOrders[3], status: "CLOSED" },
    ]) {
      fetchMock.mockResolvedValueOnce(
        jsonResponse({ workOrders: [invalid], limit: 50 }),
      );
      await expect(getWorkOrders()).rejects.toThrow("unexpected payload");
    }
  });

  it.each([openDetail, assignedDetail, inProgressDetail, doneDetail])(
    "WO-04: reads coherent $status detail with the complete history",
    async (detail) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValue(jsonResponse(detail));
      vi.stubGlobal("fetch", fetchMock);
      const controller = new AbortController();

      await expect(
        getWorkOrderDetail(workOrderId, controller.signal),
      ).resolves.toEqual(detail);
      expect(fetchMock).toHaveBeenCalledWith(
        `/api/v1/work-orders/${workOrderId}`,
        {
          method: "GET",
          headers: { Accept: "application/json" },
          credentials: "same-origin",
          signal: controller.signal,
        },
      );
    },
  );

  it("WO-04: preserves a legacy unknown assignment actor at its original time", async () => {
    const legacy = {
      ...assignedDetail,
      updatedAt: "2026-08-26T08:02:00Z",
      history: [{ ...assignedDetail.history[0], actor: null }],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(legacy)),
    );

    await expect(getWorkOrderDetail(workOrderId)).resolves.toEqual(legacy);
  });

  it("WO-04: permits equal instants and compares history at nanosecond precision", async () => {
    const detail = {
      ...inProgressDetail,
      updatedAt: "2026-08-26T08:01:00.000000002Z",
      history: [
        {
          ...assignedDetail.history[0],
          transitionedAt: "2026-08-26T10:01:00.000000002+02:00",
        },
        {
          ...inProgressDetail.history[1],
          transitionedAt: "2026-08-26T08:01:00.000000002Z",
        },
      ],
    };
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(detail));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getWorkOrderDetail(workOrderId)).resolves.toEqual(detail);
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        ...detail,
        history: [
          detail.history[0],
          {
            ...detail.history[1],
            transitionedAt: "2026-08-26T08:01:00.000000001Z",
          },
        ],
      }),
    );
    await expect(getWorkOrderDetail(workOrderId)).rejects.toThrow(
      "unexpected payload",
    );
  });

  it.each([
    ["missing history", assignedWorkOrder],
    ["missing transition", { ...assignedDetail, history: [] }],
    [
      "extra transition",
      { ...assignedDetail, history: inProgressDetail.history },
    ],
    ["extra detail field", { ...assignedDetail, organisationId: "unexpected" }],
    [
      "extra history field",
      {
        ...assignedDetail,
        history: [{ ...assignedDetail.history[0], actorEmail: "unexpected" }],
      },
    ],
    [
      "extra actor field",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            actor: { ...assignedDetail.history[0].actor, email: "unexpected" },
          },
        ],
      },
    ],
    [
      "sequence gap",
      {
        ...assignedDetail,
        history: [{ ...assignedDetail.history[0], sequenceNumber: 2 }],
      },
    ],
    [
      "reversed transition",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            fromStatus: "ASSIGNED",
            toStatus: "OPEN",
          },
        ],
      },
    ],
    [
      "skipped transition",
      {
        ...inProgressDetail,
        history: [
          assignedDetail.history[0],
          { ...inProgressDetail.history[1], fromStatus: "OPEN" },
        ],
      },
    ],
    [
      "invalid actor",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            actor: { id: "invalid", displayName: "Nora Admin" },
          },
        ],
      },
    ],
    [
      "untrimmed actor",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            actor: {
              ...assignedDetail.history[0].actor,
              displayName: " Nora Admin ",
            },
          },
        ],
      },
    ],
    [
      "unknown start actor",
      {
        ...inProgressDetail,
        history: [
          assignedDetail.history[0],
          { ...inProgressDetail.history[1], actor: null },
        ],
      },
    ],
    [
      "unknown completion actor",
      {
        ...doneDetail,
        history: [
          ...inProgressDetail.history,
          { ...doneDetail.history[2], actor: null },
        ],
      },
    ],
    [
      "another technician actor",
      {
        ...inProgressDetail,
        history: [
          assignedDetail.history[0],
          {
            ...inProgressDetail.history[1],
            actor: assignedDetail.history[0].actor,
          },
        ],
      },
    ],
    [
      "transition before creation",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            transitionedAt: "2026-08-26T07:59:59Z",
          },
        ],
      },
    ],
    [
      "transition after update",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            transitionedAt: "2026-08-26T08:01:00.000000001Z",
          },
        ],
      },
    ],
    [
      "invalid calendar date",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            transitionedAt: "2026-02-30T08:00:00Z",
          },
        ],
      },
    ],
    [
      "invalid timestamp offset",
      {
        ...assignedDetail,
        history: [
          {
            ...assignedDetail.history[0],
            transitionedAt: "2026-08-26T08:00:00+18:01",
          },
        ],
      },
    ],
  ])("WO-04: rejects detail with %s", async (_label, detail) => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(detail)),
    );
    await expect(getWorkOrderDetail(workOrderId)).rejects.toThrow(
      "unexpected payload",
    );
  });

  it("WO-04: rejects an unattributed new assignment response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          ...assignedDetail,
          history: [{ ...assignedDetail.history[0], actor: null }],
        }),
      ),
    );
    await expect(
      assignWorkOrder(workOrderId, technicianId, 0, csrfToken),
    ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
  });

  const transitions = [
    {
      command: "start",
      execute: startWorkOrder,
      expectedVersion: 1,
      detail: inProgressDetail,
    },
    {
      command: "complete",
      execute: completeWorkOrder,
      expectedVersion: 2,
      detail: doneDetail,
    },
  ] as const;

  it.each(transitions)(
    "WO-03: posts only the expected version to $command with CSRF",
    async ({ command, execute, expectedVersion, detail }) => {
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockResolvedValue(jsonResponse(detail));
      vi.stubGlobal("fetch", fetchMock);
      const controller = new AbortController();

      await expect(
        execute(workOrderId, expectedVersion, csrfToken, controller.signal),
      ).resolves.toEqual(detail);
      expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
        `/api/v1/work-orders/${workOrderId}/${command}`,
        {
          method: "POST",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json",
            "X-CSRF-TOKEN": "csrf-token-value",
          },
          credentials: "same-origin",
          body: JSON.stringify({ expectedVersion }),
          signal: controller.signal,
        },
      );
    },
  );

  it.each(transitions)(
    "AUTH-03/WO-05: classifies $command denials and conflicts without retry",
    async ({ execute, expectedVersion }) => {
      for (const [code, status, ErrorType] of [
        ["AUTHENTICATION_REQUIRED", 401, WorkOrderSessionExpiredError],
        ["CSRF_REJECTED", 403, WorkOrderRequestVerificationError],
        ["ACCESS_DENIED", 403, WorkOrderForbiddenError],
        ["WORK_ORDER_NOT_FOUND", 404, WorkOrderNotFoundError],
        ["WORK_ORDER_STATE_CONFLICT", 409, WorkOrderStateConflictError],
        ["INVALID_REQUEST", 400, WorkOrderCommandUncertainError],
        ["UNKNOWN_CONFLICT", 409, WorkOrderCommandUncertainError],
      ] as const) {
        const fetchMock = vi
          .fn<typeof fetch>()
          .mockResolvedValue(problemResponse(code, status));
        vi.stubGlobal("fetch", fetchMock);
        await expect(
          execute(workOrderId, expectedVersion, csrfToken),
        ).rejects.toBeInstanceOf(ErrorType);
        expect(fetchMock).toHaveBeenCalledOnce();
      }
    },
  );

  it.each(transitions)(
    "WO-05: treats lost or incoherent $command responses as uncertain",
    async ({ execute, expectedVersion, detail }) => {
      const fetchMock = vi.fn<typeof fetch>();
      vi.stubGlobal("fetch", fetchMock);
      fetchMock.mockRejectedValueOnce(new TypeError("response lost"));
      await expect(
        execute(workOrderId, expectedVersion, csrfToken),
      ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
      for (const response of [
        jsonResponse(detail, 201),
        jsonResponse({ ...detail, history: [] }),
        jsonResponse({ ...detail, id: technicianId }),
        jsonResponse(assignedDetail),
        new Response("unavailable", { status: 502 }),
        new Response(JSON.stringify(detail), {
          headers: { "Content-Type": "text/plain" },
        }),
      ]) {
        fetchMock.mockResolvedValueOnce(response);
        await expect(
          execute(workOrderId, expectedVersion, csrfToken),
        ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
      }
      fetchMock.mockResolvedValueOnce(jsonResponse(detail));
      await expect(
        execute(workOrderId, expectedVersion - 1, csrfToken),
      ).rejects.toBeInstanceOf(WorkOrderCommandUncertainError);
      expect(fetchMock).toHaveBeenCalledTimes(8);
    },
  );

  it.each(transitions)(
    "WO-05: rejects invalid $command input before fetching",
    async ({ execute }) => {
      const fetchMock = vi.fn<typeof fetch>();
      vi.stubGlobal("fetch", fetchMock);
      for (const version of [
        -1,
        0.5,
        NaN,
        Infinity,
        Number.MAX_SAFE_INTEGER + 1,
      ]) {
        await expect(execute(workOrderId, version, csrfToken)).rejects.toThrow(
          "non-negative expected work-order version",
        );
      }
      await expect(execute("invalid-id", 1, csrfToken)).rejects.toThrow(
        "valid work-order identifier",
      );
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );
});
