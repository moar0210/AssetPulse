import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
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

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
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
  onSessionExpired = () => {},
) {
  const view = render(
    <AlertPanel
      roleCode={roleCode}
      csrfToken={csrfToken}
      onSessionExpired={onSessionExpired}
      createEventSource={createEventSource}
    />,
  );
  fireEvent.click(
    await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    }),
  );
  await screen.findByRole("heading", { name: "High bearing temperature" });
  return view;
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

  it.each(["confirmed", "empty", "failed", "forbidden"] as const)(
    "WO-05: preserves keyboard retry focus through a %s creation recovery",
    async (outcome) => {
      const recovery = deferred<Response>();
      let queueReads = 0;
      const fetchMock = installFetch(async (url) => {
        if (url === "/api/v1/work-orders") {
          throw new TypeError("response lost");
        }
        if (url === "/api/v1/work-orders?limit=50") {
          queueReads += 1;
          if (queueReads === 1) {
            throw new TypeError("queue unavailable");
          }
          return recovery.promise;
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      await openAlert("OPERATIONS_ADMIN");
      fireEvent.click(
        screen.getByRole("button", { name: "Create work order" }),
      );
      const retryButton = await screen.findByRole("button", {
        name: "Retry authoritative check",
      });

      retryButton.focus();
      fireEvent.click(retryButton);
      const feedback = screen.getByText(
        /Checking the saved work-order queue before another action/i,
      );
      expect(retryButton).not.toBeInTheDocument();
      expect(feedback).toHaveFocus();
      expect(
        screen.getByRole("button", { name: "Checking saved work orders…" }),
      ).toBeDisabled();
      await act(async () => {
        if (outcome === "failed") {
          recovery.reject(new TypeError("queue still unavailable"));
        } else {
          recovery.resolve(
            outcome === "forbidden"
              ? problemResponse("ACCESS_DENIED", 403)
              : jsonResponse({
                  workOrders: outcome === "confirmed" ? [workOrder] : [],
                  limit: 50,
                }),
          );
        }
      });

      const finalFeedback = screen.getByText(
        outcome === "confirmed"
          ? /saved work-order queue confirms/i
          : outcome === "empty"
            ? /no matching work order was visible in the bounded queue/i
            : outcome === "forbidden"
              ? /did not grant access to recover/i
              : /saved work-order queue could not be confirmed/i,
      );
      expect(finalFeedback).toHaveFocus();
      expect(
        screen.getByRole("button", {
          name:
            outcome === "confirmed"
              ? "Work order created"
              : outcome === "forbidden"
                ? "Create unavailable"
                : "Authoritative check required",
        }),
      ).toBeDisabled();
      const finalRetry = screen.queryByRole("button", {
        name: "Retry authoritative check",
      });
      if (outcome === "empty" || outcome === "failed") {
        expect(finalRetry).toBeVisible();
      } else {
        expect(finalRetry).not.toBeInTheDocument();
      }
      expect(queueReads).toBe(2);
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
    },
  );

  it("WO-05: preserves deliberately moved focus when creation recovery completes", async () => {
    const recovery = deferred<Response>();
    let queueReads = 0;
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/work-orders") {
        throw new TypeError("response lost");
      }
      if (url === "/api/v1/work-orders?limit=50") {
        queueReads += 1;
        if (queueReads === 1) {
          throw new TypeError("queue unavailable");
        }
        return recovery.promise;
      }
      throw new Error(`Unexpected URL ${url}`);
    });
    await openAlert("OPERATIONS_ADMIN");
    fireEvent.click(screen.getByRole("button", { name: "Create work order" }));
    const retryButton = await screen.findByRole("button", {
      name: "Retry authoritative check",
    });
    retryButton.focus();
    fireEvent.click(retryButton);
    const backButton = screen.getByRole("button", { name: "Back to alerts" });
    backButton.focus();

    await act(async () =>
      recovery.resolve(jsonResponse({ workOrders: [workOrder], limit: 50 })),
    );

    expect(screen.getByText(/saved work-order queue confirms/i)).toBeVisible();
    expect(backButton).toHaveFocus();
    expect(
      fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
    ).toHaveLength(1);
  });

  it.each(["create", "recovery"] as const)(
    "WO-05: bounds the %s timeout without replaying creation",
    async (operation) => {
      let timedSignal: AbortSignal | null | undefined;
      const fetchMock = installFetch(async (url, init) => {
        if (
          (operation === "create" && url === "/api/v1/work-orders") ||
          (operation === "recovery" && url === "/api/v1/work-orders?limit=50")
        ) {
          timedSignal = init?.signal;
          return new Promise<Response>((_resolve, reject) => {
            timedSignal?.addEventListener(
              "abort",
              () => reject(new DOMException("Aborted", "AbortError")),
              { once: true },
            );
          });
        }
        if (url === "/api/v1/work-orders") {
          throw new TypeError("response lost");
        }
        if (url === "/api/v1/work-orders?limit=50") {
          return jsonResponse({ workOrders: [workOrder], limit: 50 });
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      await openAlert("OPERATIONS_ADMIN");
      vi.useFakeTimers();
      try {
        fireEvent.click(
          screen.getByRole("button", { name: "Create work order" }),
        );
        await act(async () => {
          await vi.advanceTimersByTimeAsync(4_999);
        });
        expect(timedSignal?.aborted).toBe(false);
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1);
        });
      } finally {
        vi.useRealTimers();
      }

      expect(timedSignal?.aborted).toBe(true);
      expect(
        await screen.findByRole("button", {
          name:
            operation === "create"
              ? "Work order created"
              : "Authoritative check required",
        }),
      ).toBeDisabled();
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
      expect(
        fetchMock.mock.calls.filter(
          ([input]) => String(input) === "/api/v1/work-orders?limit=50",
        ),
      ).toHaveLength(1);
    },
  );

  it.each(["create", "recovery"] as const)(
    "WO-05: aborts %s on unmount and ignores its late authentication denial",
    async (operation) => {
      const pending = deferred<Response>();
      const onSessionExpired = vi.fn();
      let pendingSignal: AbortSignal | null | undefined;
      const fetchMock = installFetch(async (url, init) => {
        if (
          (operation === "create" && url === "/api/v1/work-orders") ||
          (operation === "recovery" && url === "/api/v1/work-orders?limit=50")
        ) {
          pendingSignal = init?.signal;
          return pending.promise;
        }
        if (url === "/api/v1/work-orders") {
          throw new TypeError("response lost");
        }
        throw new Error(`Unexpected URL ${url}`);
      });
      const { unmount } = await openAlert("OPERATIONS_ADMIN", onSessionExpired);
      fireEvent.click(
        screen.getByRole("button", { name: "Create work order" }),
      );
      await waitFor(() => expect(pendingSignal).toBeInstanceOf(AbortSignal));

      unmount();
      expect(pendingSignal?.aborted).toBe(true);
      const callsAtUnmount = fetchMock.mock.calls.length;
      await act(async () => pending.resolve(jsonResponse({}, 401)));

      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(callsAtUnmount);
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
    },
  );

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
