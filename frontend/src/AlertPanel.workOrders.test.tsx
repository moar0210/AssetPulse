import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AlertPanel } from "./AlertPanel";
import type { AlertEventSourceFactory } from "./api/alerts";

const alertId = "50000000-0000-0000-0000-000000000001";
const workOrderId = "60000000-0000-0000-0000-000000000001";
const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
} as const;

const context = {
  asset: {
    id: "20000000-0000-0000-0000-000000000001",
    assetCode: "PUMP-101",
    name: "Boiler Feed Pump",
  },
  sensor: {
    id: "30000000-0000-0000-0000-000000000001",
    sensorKey: "PUMP-101-TEMP",
    name: "Bearing Temperature",
    measurementType: "TEMPERATURE",
    unit: "CELSIUS",
  },
  thresholdRule: {
    id: "40000000-0000-0000-0000-000000000001",
    ruleCode: "PUMP-101-HIGH-TEMP",
    name: "High bearing temperature",
    comparison: "GREATER_THAN_OR_EQUAL_TO",
    thresholdValue: 80,
    cooldownSeconds: 300,
  },
} as const;

const summary = {
  id: alertId,
  status: "OPEN",
  occurrenceCount: 1,
  lastOccurredAt: "2026-08-26T08:00:00Z",
  context,
} as const;

const detail = {
  ...summary,
  firstOccurredAt: "2026-08-26T08:00:00Z",
  cooldownUntil: "2026-08-26T08:05:00Z",
  createdAt: "2026-08-26T08:00:00Z",
  updatedAt: "2026-08-26T08:00:00Z",
  history: [],
} as const;

const workOrder = {
  id: workOrderId,
  alertId,
  status: "OPEN",
  version: 0,
  assignedTechnician: null,
  createdAt: "2026-08-26T08:01:00Z",
  updatedAt: "2026-08-26T08:01:00Z",
  context: {
    assetId: context.asset.id,
    assetCode: context.asset.assetCode,
    assetName: context.asset.name,
    ruleName: context.thresholdRule.name,
  },
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

class QuietEventSource {
  close() {}
  addEventListener() {}
}

const createEventSource: AlertEventSourceFactory = () =>
  new QuietEventSource() as unknown as EventSource;

function installFetch(
  workOrderHandler: (
    url: string,
    init: RequestInit | undefined,
  ) => Promise<Response>,
) {
  const fetchMock = vi.fn<typeof fetch>((input, init) => {
    const url = String(input);
    if (url === "/api/v1/alerts?limit=50") {
      return Promise.resolve(jsonResponse({ alerts: [summary], limit: 50 }));
    }
    if (url === `/api/v1/alerts/${alertId}`) {
      return Promise.resolve(jsonResponse(detail));
    }
    return workOrderHandler(url, init);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function openAlert(
  roleCode: "OPERATIONS_ADMIN" | "TECHNICIAN" | "VIEWER",
) {
  render(
    <AlertPanel
      roleCode={roleCode}
      csrfToken={csrfToken}
      onSessionExpired={() => {}}
      createEventSource={createEventSource}
    />,
  );
  fireEvent.click(
    await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    }),
  );
  await screen.findByRole("heading", { name: "High bearing temperature" });
}

describe("alert-to-work-order action", () => {
  it("creates a work order with the in-memory CSRF token", async () => {
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/work-orders") {
        return jsonResponse({ ...workOrder, history: [] }, 201);
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openAlert("OPERATIONS_ADMIN");

    fireEvent.click(screen.getByRole("button", { name: "Create work order" }));
    expect(
      await screen.findByText(/Work order created. Open Work orders/i),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Work order created" }),
    ).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/work-orders", {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf-token-value",
      },
      credentials: "same-origin",
      body: JSON.stringify({ alertId }),
      signal: expect.any(AbortSignal),
    });
  });

  it("never renders create controls for technician or viewer roles", async () => {
    installFetch(async (url) => {
      throw new Error(`Unexpected work-order call ${url}`);
    });
    await openAlert("VIEWER");

    expect(
      screen.queryByText("Maintenance work order"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /create work order/i }),
    ).not.toBeInTheDocument();
  });

  it("recovers an authoritative duplicate without issuing another create", async () => {
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/work-orders") {
        return problemResponse("WORK_ORDER_ALREADY_EXISTS", 409);
      }
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [workOrder], limit: 50 });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openAlert("OPERATIONS_ADMIN");

    fireEvent.click(screen.getByRole("button", { name: "Create work order" }));
    expect(
      await screen.findByText(/already has a work order. Open Work orders/i),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Work order already exists" }),
    ).toBeDisabled();
    expect(
      fetchMock.mock.calls.filter(
        ([input]) => String(input) === "/api/v1/work-orders",
      ),
    ).toHaveLength(1);
  });

  it("recovers a lost create response from the authoritative queue", async () => {
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/work-orders") {
        throw new TypeError("response lost");
      }
      if (url === "/api/v1/work-orders?limit=50") {
        return jsonResponse({ workOrders: [workOrder], limit: 50 });
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openAlert("OPERATIONS_ADMIN");

    fireEvent.click(screen.getByRole("button", { name: "Create work order" }));
    expect(
      await screen.findByText(/saved work-order queue confirms/i),
    ).toBeVisible();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/work-orders?limit=50",
      expect.objectContaining({ method: "GET" }),
    );
  });

  it("expires the app session when create authentication is rejected", async () => {
    const onSessionExpired = vi.fn();
    installFetch(async (url) => {
      if (url === "/api/v1/work-orders") {
        return jsonResponse({}, 401);
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
        createEventSource={createEventSource}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", { name: /view open alert/i }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Create work order" }),
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
  });
});
