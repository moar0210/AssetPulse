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
  lastOccurredAt: "2026-08-23T10:03:00Z",
  context,
} as const;

const openDetail = {
  ...summary,
  firstOccurredAt: "2026-08-23T10:01:00Z",
  cooldownUntil: "2026-08-23T10:08:00Z",
  createdAt: "2026-08-23T10:01:01Z",
  updatedAt: "2026-08-23T10:03:01Z",
  history: [],
} as const;

const acknowledgedDetail = {
  ...openDetail,
  status: "ACKNOWLEDGED",
  updatedAt: "2026-08-23T10:04:00Z",
  history: [
    {
      sequenceNumber: 1,
      fromStatus: "OPEN",
      toStatus: "ACKNOWLEDGED",
      actor: {
        id: "10000000-0000-0000-0000-000000000001",
        displayName: "Nora Admin",
      },
      transitionedAt: "2026-08-23T10:04:00Z",
    },
  ],
} as const;

const resolvedDetail = {
  ...acknowledgedDetail,
  status: "RESOLVED",
  updatedAt: "2026-08-23T10:05:00Z",
  history: [
    ...acknowledgedDetail.history,
    {
      sequenceNumber: 2,
      fromStatus: "ACKNOWLEDGED",
      toStatus: "RESOLVED",
      actor: {
        id: "10000000-0000-0000-0000-000000000001",
        displayName: "Nora Admin",
      },
      transitionedAt: "2026-08-23T10:05:00Z",
    },
  ],
} as const;

const refreshedOpenDetail = {
  ...openDetail,
  occurrenceCount: 2,
  lastOccurredAt: "2026-08-23T10:04:00Z",
  cooldownUntil: "2026-08-23T10:09:00Z",
  updatedAt: "2026-08-23T10:04:01Z",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

class FakeAlertEventSource {
  readonly close = vi.fn();
  private readonly listeners = new Map<string, Set<EventListener>>();

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    const listeners = this.listeners.get(type) ?? new Set<EventListener>();
    listeners.add(listener as EventListener);
    this.listeners.set(type, listeners);
  }

  emit(type: string, data?: unknown) {
    const event =
      data === undefined
        ? new Event(type)
        : new MessageEvent(type, { data: JSON.stringify(data) });
    this.listeners.get(type)?.forEach((listener) => listener(event));
  }
}

function streamHarness() {
  const source = new FakeAlertEventSource();
  const factory = vi.fn<AlertEventSourceFactory>(
    () => source as unknown as EventSource,
  );
  return { source, factory };
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

function queueResponse(alerts: readonly unknown[] = [summary]) {
  return jsonResponse({ alerts, limit: 50 });
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

describe("live alert experience", () => {
  it("renders a bounded queue and retains an explicit empty live state", async () => {
    const { factory } = streamHarness();
    installFetch(async () => queueResponse());
    const { rerender } = render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );

    expect(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    ).toBeEnabled();
    expect(screen.getByText("1 occurrence")).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent(
      "Live updates connecting",
    );

    installFetch(async () => queueResponse([]));
    rerender(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Refresh alerts" }));
    expect(
      await screen.findByText(/No alerts are available for this organisation/i),
    ).toBeVisible();
  });

  it("refreshes authoritative state on open, reconnect, and typed changes", async () => {
    const { source, factory } = streamHarness();
    let queueReads = 0;
    installFetch(async () => {
      queueReads += 1;
      return queueResponse([
        {
          ...summary,
          occurrenceCount: queueReads,
          lastOccurredAt: `2026-08-23T10:0${queueReads + 2}:00Z`,
        },
      ]);
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    expect(await screen.findByText("1 occurrence")).toBeVisible();

    await act(async () => source.emit("open"));
    expect(await screen.findByText("2 occurrences")).toBeVisible();
    expect(screen.getByText("Live updates connected")).toBeVisible();

    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "OCCURRENCE_RECORDED",
      }),
    );
    expect(await screen.findByText("3 occurrences")).toBeVisible();

    await act(async () => source.emit("error"));
    expect(screen.getByText(/Live updates reconnecting/)).toBeVisible();
    await waitFor(() => expect(queueReads).toBe(4));

    await act(async () => source.emit("open"));
    await waitFor(() => expect(queueReads).toBe(5));
  });

  it("coalesces bursty queue invalidations into one trailing refresh", async () => {
    const { source, factory } = streamHarness();
    const firstRead = deferred<Response>();
    const trailingRead = deferred<Response>();
    let queueReads = 0;
    installFetch(async () => {
      queueReads += 1;
      if (queueReads === 1) {
        return firstRead.promise;
      }
      if (queueReads === 2) {
        return trailingRead.promise;
      }
      throw new Error("unexpected extra queue read");
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    await waitFor(() => expect(queueReads).toBe(1));

    await act(async () => source.emit("open"));
    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "OCCURRENCE_RECORDED",
      }),
    );
    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "STATUS_CHANGED",
      }),
    );
    expect(queueReads).toBe(1);

    await act(async () => firstRead.resolve(queueResponse()));
    await waitFor(() => expect(queueReads).toBe(2));
    expect(queueReads).toBe(2);

    await act(async () => trailingRead.resolve(queueResponse()));
    expect(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    ).toBeEnabled();
    expect(queueReads).toBe(2);
  });

  it("marks malformed stream data as degraded without claiming a reconnect", async () => {
    const { source, factory } = streamHarness();
    installFetch(async () => queueResponse());

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    });

    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "UNKNOWN",
      }),
    );

    expect(
      screen.getByText(/Live updates received invalid data/),
    ).toBeVisible();
    expect(screen.queryByText(/Live updates reconnecting/)).toBeNull();
    expect(source.close).not.toHaveBeenCalled();
  });

  it("refreshes the selected detail whenever the stream reopens", async () => {
    const { source, factory } = streamHarness();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        return jsonResponse(
          detailReads === 1 ? openDetail : refreshedOpenDetail,
        );
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    await screen.findByRole("heading", { name: "High bearing temperature" });
    expect(detailReads).toBe(1);

    await act(async () => source.emit("open"));

    await waitFor(() => expect(detailReads).toBe(2));
    expect(screen.getByText("2")).toBeVisible();
  });

  it("coalesces bursty selected-detail invalidations into one trailing refresh", async () => {
    const { source, factory } = streamHarness();
    const firstRead = deferred<Response>();
    const trailingRead = deferred<Response>();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        if (detailReads === 1) {
          return firstRead.promise;
        }
        if (detailReads === 2) {
          return trailingRead.promise;
        }
        throw new Error("unexpected extra detail read");
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    await waitFor(() => expect(detailReads).toBe(1));

    await act(async () => source.emit("open"));
    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "OCCURRENCE_RECORDED",
      }),
    );
    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "STATUS_CHANGED",
      }),
    );
    expect(detailReads).toBe(1);

    await act(async () => firstRead.resolve(jsonResponse(openDetail)));
    await waitFor(() => expect(detailReads).toBe(2));
    expect(detailReads).toBe(2);

    await act(async () =>
      trailingRead.resolve(jsonResponse(refreshedOpenDetail)),
    );
    expect(
      await screen.findByRole("heading", { name: "High bearing temperature" }),
    ).toBeVisible();
    expect(screen.getByText("2")).toBeVisible();
    expect(detailReads).toBe(2);
  });

  it("shows exact detail/history and submits the state-correct admin command", async () => {
    const { factory } = streamHarness();
    const fetchMock = installFetch(async (url, init) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        expect(init?.method).toBe("POST");
        return jsonResponse(acknowledgedDetail);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        return jsonResponse(openDetail);
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );

    expect(
      await screen.findByRole("heading", { name: "High bearing temperature" }),
    ).toBeVisible();
    expect(
      screen.getByText("No status transitions have been recorded."),
    ).toBeVisible();
    expect(screen.getByText("PUMP-101-TEMP")).toBeVisible();

    const acknowledgeButton = screen.getByRole("button", {
      name: "Acknowledge alert",
    });
    acknowledgeButton.focus();
    fireEvent.click(acknowledgeButton);
    const acknowledgedFeedback = await screen.findByText("Alert acknowledged.");
    expect(acknowledgedFeedback).toBeVisible();
    await waitFor(() => expect(acknowledgedFeedback).toHaveFocus());
    expect(screen.getByText("Nora Admin")).toBeVisible();
    expect(screen.getByRole("button", { name: "Resolve alert" })).toBeEnabled();

    const commandCall = fetchMock.mock.calls.find(
      ([url]) => url === `/api/v1/alerts/${alertId}/acknowledge`,
    );
    expect(commandCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-value" }),
    );
  });

  it("moves focus to stable feedback after resolving removes the action", async () => {
    const { factory } = streamHarness();
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/resolve`) {
        return jsonResponse(resolvedDetail);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        return jsonResponse(acknowledgedDetail);
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    const resolveButton = await screen.findByRole("button", {
      name: "Resolve alert",
    });
    resolveButton.focus();
    fireEvent.click(resolveButton);

    const feedback = await screen.findByText("Alert resolved.");
    await waitFor(() => expect(feedback).toHaveFocus());
    expect(screen.queryByRole("button", { name: "Resolve alert" })).toBeNull();
  });

  it("cannot let an older detail read overwrite a confirmed command response", async () => {
    const { source, factory } = streamHarness();
    const staleRead = deferred<Response>();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return jsonResponse(acknowledgedDetail);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        return detailReads === 1 ? jsonResponse(openDetail) : staleRead.promise;
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    await screen.findByRole("button", { name: "Acknowledge alert" });

    await act(async () => source.emit("open"));
    await waitFor(() => expect(detailReads).toBe(2));
    fireEvent.click(screen.getByRole("button", { name: "Acknowledge alert" }));
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();

    await act(async () => staleRead.resolve(jsonResponse(openDetail)));
    await act(async () => Promise.resolve());
    expect(screen.getByRole("button", { name: "Resolve alert" })).toBeEnabled();
    expect(
      screen.queryByRole("button", { name: "Acknowledge alert" }),
    ).toBeNull();
  });

  it("moves focus into detail and restores it to the originating row", async () => {
    const { factory } = streamHarness();
    installFetch(async (url) =>
      url === `/api/v1/alerts/${alertId}`
        ? jsonResponse(openDetail)
        : queueResponse(),
    );

    render(
      <AlertPanel
        roleCode="VIEWER"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    const row = await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    });
    row.focus();
    fireEvent.click(row);

    const detail = await screen.findByRole("region", {
      name: "High bearing temperature",
    });
    await waitFor(() => expect(detail).toHaveFocus());
    fireEvent.click(screen.getByRole("button", { name: "Back to alerts" }));

    const restoredRow = await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    });
    await waitFor(() => expect(restoredRow).toHaveFocus());
  });

  it("hides mutations from non-admin roles while retaining detail", async () => {
    const { factory } = streamHarness();
    installFetch(async (url) =>
      url === `/api/v1/alerts/${alertId}`
        ? jsonResponse(openDetail)
        : queueResponse(),
    );

    render(
      <AlertPanel
        roleCode="VIEWER"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    expect(
      await screen.findByRole("heading", { name: "High bearing temperature" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Acknowledge alert" }),
    ).toBeNull();
  });

  it("recovers a stale command conflict from authoritative detail", async () => {
    const { factory } = streamHarness();
    let detailReads = 0;
    const recovery = deferred<Response>();
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return jsonResponse({}, 409);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        return detailReads === 1 ? jsonResponse(openDetail) : recovery.promise;
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "changed in another client",
    );
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();

    await act(async () => recovery.resolve(jsonResponse(acknowledgedDetail)));
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "latest saved state is now loaded",
    );
    expect(detailReads).toBe(2);
  });

  it("unlocks after the first successful recovery snapshot while a trailing refresh continues", async () => {
    const { source, factory } = streamHarness();
    const recovery = deferred<Response>();
    const trailingRefresh = deferred<Response>();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return jsonResponse({}, 409);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        if (detailReads === 1) {
          return jsonResponse(openDetail);
        }
        if (detailReads === 2) {
          return recovery.promise;
        }
        if (detailReads === 3) {
          return trailingRefresh.promise;
        }
        throw new Error("unexpected extra detail read");
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );
    await waitFor(() => expect(detailReads).toBe(2));

    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "STATUS_CHANGED",
      }),
    );
    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "OCCURRENCE_RECORDED",
      }),
    );
    expect(detailReads).toBe(2);

    await act(async () => recovery.resolve(jsonResponse(acknowledgedDetail)));
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    await waitFor(() => expect(detailReads).toBe(3));
    expect(screen.getByRole("button", { name: "Resolve alert" })).toBeEnabled();

    await act(async () =>
      trailingRefresh.resolve(jsonResponse(acknowledgedDetail)),
    );
    expect(detailReads).toBe(3);
  });

  it("recovers an uncertain lost response before offering another command", async () => {
    const { factory } = streamHarness();
    let detailReads = 0;
    const recovery = deferred<Response>();
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        throw new TypeError("response lost");
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        return detailReads === 1 ? jsonResponse(openDetail) : recovery.promise;
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not be confirmed",
    );
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();

    await act(async () => recovery.resolve(jsonResponse(acknowledgedDetail)));
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    expect(detailReads).toBe(2);
  });

  it("keeps a stale command locked until an explicit recovery retry succeeds", async () => {
    const { factory } = streamHarness();
    let detailReads = 0;
    let commandCalls = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        commandCalls += 1;
        return jsonResponse({}, 409);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        if (detailReads === 1) {
          return jsonResponse(openDetail);
        }
        if (detailReads === 2) {
          return jsonResponse({}, 503);
        }
        return jsonResponse(acknowledgedDetail);
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    const lockedCommand = await screen.findByRole("button", {
      name: "Latest state required",
    });
    expect(lockedCommand).toBeDisabled();
    expect(
      screen.getByText(/latest saved state could not be loaded/i),
    ).toBeVisible();
    fireEvent.click(lockedCommand);
    expect(commandCalls).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: "Retry latest state" }));
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    expect(detailReads).toBe(3);
    expect(commandCalls).toBe(1);
  });

  it("waits for the dedicated retry snapshot instead of an older ordinary detail read", async () => {
    const { source, factory } = streamHarness();
    const ordinaryRefresh = deferred<Response>();
    const retryRefresh = deferred<Response>();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return jsonResponse({}, 409);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        if (detailReads === 1) {
          return jsonResponse(openDetail);
        }
        if (detailReads === 2) {
          return jsonResponse({}, 503);
        }
        if (detailReads === 3) {
          return ordinaryRefresh.promise;
        }
        if (detailReads === 4) {
          return retryRefresh.promise;
        }
        throw new Error("unexpected extra detail read");
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );
    const retryButton = await screen.findByRole("button", {
      name: "Retry latest state",
    });

    await act(async () =>
      source.emit("alert-changed", {
        alertId,
        changeType: "STATUS_CHANGED",
      }),
    );
    await waitFor(() => expect(detailReads).toBe(3));

    fireEvent.click(retryButton);
    await waitFor(() => expect(detailReads).toBe(4));
    await act(async () =>
      ordinaryRefresh.resolve(jsonResponse(acknowledgedDetail)),
    );
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Resolve alert" })).toBeNull();

    await act(async () =>
      retryRefresh.resolve(jsonResponse(acknowledgedDetail)),
    );
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    expect(detailReads).toBe(4);
  });

  it("moves retry focus to stable feedback and preserves it when recovery resolves the alert", async () => {
    const { factory } = streamHarness();
    const retryRecovery = deferred<Response>();
    let detailReads = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return jsonResponse({}, 409);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        detailReads += 1;
        if (detailReads === 1) {
          return jsonResponse(openDetail);
        }
        if (detailReads === 2) {
          return jsonResponse({}, 503);
        }
        return retryRecovery.promise;
      }
      return queueResponse();
    });

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    const retryButton = await screen.findByRole("button", {
      name: "Retry latest state",
    });
    retryButton.focus();
    fireEvent.click(retryButton);

    const retryFeedback = await screen.findByText(
      "Retrying the latest saved alert state…",
    );
    expect(retryFeedback).toHaveFocus();
    expect(
      screen.queryByRole("button", { name: "Retry latest state" }),
    ).toBeNull();

    await act(async () => retryRecovery.resolve(jsonResponse(resolvedDetail)));
    const recoveredFeedback = await screen.findByText(
      /latest saved state is now loaded/i,
    );
    await waitFor(() => expect(recoveredFeedback).toHaveFocus());
    expect(screen.queryByRole("button", { name: "Resolve alert" })).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Acknowledge alert" }),
    ).toBeNull();
    expect(detailReads).toBe(3);
  });

  it("expires the session on a queue 401 and closes the stream on unmount", async () => {
    const { source, factory } = streamHarness();
    const onSessionExpired = vi.fn();
    installFetch(async () => jsonResponse({}, 401));

    const { unmount } = render(
      <AlertPanel
        roleCode="TECHNICIAN"
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
        createEventSource={factory}
      />,
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
    unmount();
    expect(source.close).toHaveBeenCalledOnce();
  });

  it("ignores every late stream callback after cleanup", async () => {
    const { source, factory } = streamHarness();
    const onSessionExpired = vi.fn();
    const fetchMock = installFetch(async () => queueResponse());
    const { unmount } = render(
      <AlertPanel
        roleCode="VIEWER"
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
        createEventSource={factory}
      />,
    );
    await screen.findByRole("button", {
      name: /view open alert for boiler feed pump/i,
    });
    const callsBeforeCleanup = fetchMock.mock.calls.length;

    unmount();
    await act(async () => {
      source.emit("open");
      source.emit("error");
      source.emit("alert-changed", {
        alertId,
        changeType: "STATUS_CHANGED",
      });
    });

    expect(source.close).toHaveBeenCalledOnce();
    expect(fetchMock).toHaveBeenCalledTimes(callsBeforeCleanup);
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("renders an explicit forbidden queue state", async () => {
    const { factory } = streamHarness();
    installFetch(async () => jsonResponse({}, 403));

    render(
      <AlertPanel
        roleCode="VIEWER"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "do not have permission to view this alert queue",
    );
    expect(
      screen.getByRole("heading", { name: "Alert access denied" }),
    ).toBeVisible();
  });

  it("shows a non-leaking alert-not-found state and returns to the queue", async () => {
    const { factory } = streamHarness();
    installFetch(async (url) =>
      url === `/api/v1/alerts/${alertId}`
        ? jsonResponse(
            { detail: "Riverside secret compressor", code: "ALERT_NOT_FOUND" },
            404,
          )
        : queueResponse(),
    );

    render(
      <AlertPanel
        roleCode="OPERATIONS_ADMIN"
        csrfToken={csrfToken}
        onSessionExpired={() => {}}
        createEventSource={factory}
      />,
    );
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    expect(
      await screen.findByRole("heading", { name: "Alert not found" }),
    ).toBeVisible();
    expect(screen.queryByText(/Riverside|compressor/)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Back to alerts" }));
    expect(
      await screen.findByRole("heading", { name: "Alerts" }),
    ).toBeVisible();
  });
});
