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
  describe("ALR-03: bounded requests and command cancellation", () => {
    function renderPanel() {
      const { source, factory } = streamHarness();
      const onSessionExpired = vi.fn();
      const view = render(
        <AlertPanel
          roleCode="OPERATIONS_ADMIN"
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
          createEventSource={factory}
        />,
      );
      return { ...view, source, onSessionExpired };
    }

    async function openDetailScreen() {
      fireEvent.click(
        await screen.findByRole("button", {
          name: /view open alert for boiler feed pump/i,
        }),
      );
      await screen.findByRole("heading", { name: "High bearing temperature" });
    }

    function abortableResponse(signal: AbortSignal | null | undefined) {
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener(
          "abort",
          () => reject(new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      });
    }

    it.each(["queue", "detail"] as const)(
      "aborts the first %s read at five seconds and permits an explicit retry",
      async (target) => {
        const targetUrl =
          target === "queue"
            ? "/api/v1/alerts?limit=50"
            : `/api/v1/alerts/${alertId}`;
        let targetReads = 0;
        let timedSignal: AbortSignal | null | undefined;
        const fetchMock = installFetch(async (url, init) => {
          expect(init?.method).toBe("GET");
          if (
            url !== "/api/v1/alerts?limit=50" &&
            url !== `/api/v1/alerts/${alertId}`
          ) {
            throw new Error(`Unexpected URL ${url}`);
          }
          if (url === targetUrl) {
            targetReads += 1;
            if (targetReads === 1) {
              timedSignal = init?.signal;
              return abortableResponse(timedSignal);
            }
          }
          return url === `/api/v1/alerts/${alertId}`
            ? jsonResponse(openDetail)
            : queueResponse();
        });

        if (target === "queue") {
          vi.useFakeTimers();
        }
        const { onSessionExpired } = renderPanel();
        if (target === "detail") {
          const row = await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          });
          vi.useFakeTimers();
          fireEvent.click(row);
        }
        try {
          expect(
            screen.getByText(
              target === "queue" ? "Loading alerts…" : "Loading alert details…",
            ),
          ).toBeVisible();
          await act(async () => vi.advanceTimersByTimeAsync(4_999));
          expect(timedSignal).toBeInstanceOf(AbortSignal);
          expect(timedSignal?.aborted).toBe(false);
          expect(targetReads).toBe(1);
          await act(async () => vi.advanceTimersByTimeAsync(1));
        } finally {
          vi.useRealTimers();
        }

        expect(timedSignal?.aborted).toBe(true);
        expect(screen.getByRole("alert")).toHaveTextContent(
          target === "queue"
            ? "We could not load alerts. Try again."
            : "We could not load this alert. Try again.",
        );
        expect(targetReads).toBe(1);
        fireEvent.click(
          screen.getByRole("button", {
            name: target === "queue" ? "Retry alerts" : "Retry alert details",
          }),
        );
        expect(
          await screen.findByRole(target === "queue" ? "button" : "heading", {
            name:
              target === "queue"
                ? /view open alert for boiler feed pump/i
                : "High bearing temperature",
          }),
        ).toBeVisible();
        expect(targetReads).toBe(2);
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(["queue", "detail"] as const)(
      "retains confirmed %s data after refresh timeout and drains one trailing SSE refresh",
      async (target) => {
        const targetUrl =
          target === "queue"
            ? "/api/v1/alerts?limit=50"
            : `/api/v1/alerts/${alertId}`;
        const trailing = deferred<Response>();
        let targetReads = 0;
        let timedSignal: AbortSignal | null | undefined;
        const fetchMock = installFetch(async (url, init) => {
          expect(init?.method).toBe("GET");
          if (
            url !== "/api/v1/alerts?limit=50" &&
            url !== `/api/v1/alerts/${alertId}`
          ) {
            throw new Error(`Unexpected URL ${url}`);
          }
          if (url === targetUrl) {
            targetReads += 1;
            if (targetReads === 2) {
              timedSignal = init?.signal;
              return abortableResponse(timedSignal);
            }
            if (targetReads === 3) {
              return trailing.promise;
            }
          }
          return url === `/api/v1/alerts/${alertId}`
            ? jsonResponse(openDetail)
            : queueResponse();
        });
        const { source, onSessionExpired } = renderPanel();
        if (target === "detail") {
          await openDetailScreen();
        } else {
          await screen.findByText("1 occurrence");
        }

        vi.useFakeTimers();
        try {
          await act(async () => source.emit("open"));
          for (let index = 0; index < 3; index += 1) {
            await act(async () =>
              source.emit("alert-changed", {
                alertId,
                changeType: "OCCURRENCE_RECORDED",
              }),
            );
          }
          expect(targetReads).toBe(2);
          await act(async () => vi.advanceTimersByTimeAsync(4_999));
          expect(timedSignal?.aborted).toBe(false);
          expect(targetReads).toBe(2);
          await act(async () => vi.advanceTimersByTimeAsync(1));
          expect(timedSignal?.aborted).toBe(true);
          expect(targetReads).toBe(3);
          expect(screen.getByRole("alert")).toHaveTextContent(
            `Live refresh failed. The last confirmed alert ${target} is still shown.`,
          );
          expect(
            target === "queue"
              ? screen.getByText("1 occurrence")
              : screen.getByRole("button", { name: "Acknowledge alert" }),
          ).toBeVisible();
          await act(async () =>
            trailing.resolve(
              target === "queue"
                ? queueResponse([{ ...summary, occurrenceCount: 2 }])
                : jsonResponse(refreshedOpenDetail),
            ),
          );
        } finally {
          vi.useRealTimers();
        }

        expect(
          target === "queue"
            ? screen.getByText("2 occurrences")
            : screen.getByText("2", { selector: ".alert-facts dd" }),
        ).toBeVisible();
        expect(screen.queryByText(/Live refresh failed/)).toBeNull();
        expect(targetReads).toBe(3);
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(["acknowledge", "resolve"] as const)(
      "recovers a timed-out %s command with one authoritative GET and no POST replay",
      async (command) => {
        const recovery = deferred<Response>();
        const initial =
          command === "acknowledge" ? openDetail : acknowledgedDetail;
        const saved =
          command === "acknowledge" ? acknowledgedDetail : resolvedDetail;
        let detailReads = 0;
        let timedSignal: AbortSignal | null | undefined;
        const fetchMock = installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}/${command}`) {
            expect(init?.method).toBe("POST");
            timedSignal = init?.signal;
            return abortableResponse(timedSignal);
          }
          if (url === `/api/v1/alerts/${alertId}`) {
            expect(init?.method).toBe("GET");
            detailReads += 1;
            return detailReads === 1 ? jsonResponse(initial) : recovery.promise;
          }
          if (url === "/api/v1/alerts?limit=50") {
            expect(init?.method).toBe("GET");
            return queueResponse();
          }
          throw new Error(`Unexpected URL ${url}`);
        });
        const { onSessionExpired } = renderPanel();
        await openDetailScreen();
        vi.useFakeTimers();
        try {
          fireEvent.click(
            screen.getByRole("button", {
              name:
                command === "acknowledge"
                  ? "Acknowledge alert"
                  : "Resolve alert",
            }),
          );
          await act(async () => vi.advanceTimersByTimeAsync(4_999));
          expect(timedSignal?.aborted).toBe(false);
          expect(detailReads).toBe(1);
          await act(async () => vi.advanceTimersByTimeAsync(1));
        } finally {
          vi.useRealTimers();
        }

        expect(timedSignal?.aborted).toBe(true);
        expect(
          screen.getByRole("button", { name: "Recovering latest state…" }),
        ).toBeDisabled();
        expect(detailReads).toBe(2);
        await act(async () => recovery.resolve(jsonResponse(saved)));
        expect(screen.getByRole("alert")).toHaveTextContent(
          "The command result could not be confirmed. The latest saved state is now loaded.",
        );
        if (command === "acknowledge") {
          expect(
            screen.getByRole("button", { name: "Resolve alert" }),
          ).toBeEnabled();
        } else {
          expect(
            screen.queryByRole("button", { name: "Resolve alert" }),
          ).toBeNull();
          expect(
            screen.getByText("Resolved", { selector: ".alert-status" }),
          ).toBeVisible();
        }
        expect(detailReads).toBe(2);
        expect(
          fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
        ).toHaveLength(1);
        expect(
          fetchMock.mock.calls.every(
            ([url, init]) =>
              init?.method ===
              (String(url) === `/api/v1/alerts/${alertId}/${command}`
                ? "POST"
                : "GET"),
          ),
        ).toBe(true);
        expect(onSessionExpired).not.toHaveBeenCalled();
      },
    );

    it.each(["acknowledge", "resolve"] as const)(
      "locks a conflicted %s command after recovery timeout until explicit GET retry",
      async (command) => {
        const initial =
          command === "acknowledge" ? openDetail : acknowledgedDetail;
        const saved =
          command === "acknowledge" ? acknowledgedDetail : resolvedDetail;
        let detailReads = 0;
        let timedSignal: AbortSignal | null | undefined;
        const fetchMock = installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}/${command}`) {
            expect(init?.method).toBe("POST");
            return jsonResponse({}, 409);
          }
          if (url === `/api/v1/alerts/${alertId}`) {
            expect(init?.method).toBe("GET");
            detailReads += 1;
            if (detailReads === 2) {
              timedSignal = init?.signal;
              return abortableResponse(timedSignal);
            }
            return jsonResponse(detailReads === 1 ? initial : saved);
          }
          if (url === "/api/v1/alerts?limit=50") {
            expect(init?.method).toBe("GET");
            return queueResponse();
          }
          throw new Error(`Unexpected URL ${url}`);
        });
        const { onSessionExpired } = renderPanel();
        await openDetailScreen();
        vi.useFakeTimers();
        try {
          await act(async () =>
            fireEvent.click(
              screen.getByRole("button", {
                name:
                  command === "acknowledge"
                    ? "Acknowledge alert"
                    : "Resolve alert",
              }),
            ),
          );
          expect(detailReads).toBe(2);
          await act(async () => vi.advanceTimersByTimeAsync(4_999));
          expect(timedSignal?.aborted).toBe(false);
          await act(async () => vi.advanceTimersByTimeAsync(1));
        } finally {
          vi.useRealTimers();
        }

        expect(timedSignal?.aborted).toBe(true);
        const lockedCommand = screen.getByRole("button", {
          name: "Latest state required",
        });
        expect(lockedCommand).toBeDisabled();
        expect(
          screen.getByText(/latest saved state could not be loaded/i),
        ).toBeVisible();
        expect(detailReads).toBe(2);
        fireEvent.click(lockedCommand);
        fireEvent.click(
          screen.getByRole("button", { name: "Retry latest state" }),
        );
        await screen.findByText(/latest saved state is now loaded/i);
        if (command === "acknowledge") {
          expect(
            screen.getByRole("button", { name: "Resolve alert" }),
          ).toBeEnabled();
        } else {
          expect(
            screen.queryByRole("button", { name: "Resolve alert" }),
          ).toBeNull();
        }
        expect(detailReads).toBe(3);
        expect(
          fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
        ).toHaveLength(1);
        expect(
          fetchMock.mock.calls.every(
            ([url, init]) =>
              init?.method ===
              (String(url) === `/api/v1/alerts/${alertId}/${command}`
                ? "POST"
                : "GET"),
          ),
        ).toBe(true);
        expect(onSessionExpired).not.toHaveBeenCalled();
      },
    );

    it.each(
      (["Back", "unmount"] as const).flatMap((navigation) =>
        (["success", "401", "409"] as const).map((outcome) => ({
          navigation,
          outcome,
        })),
      ),
    )(
      "aborts a command on $navigation and ignores its late $outcome after leaving detail",
      async ({ navigation, outcome }) => {
        const pending = deferred<Response>();
        let commandSignal: AbortSignal | null | undefined;
        let detailReads = 0;
        const fetchMock = installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
            expect(init?.method).toBe("POST");
            commandSignal = init?.signal;
            return pending.promise;
          }
          if (url === `/api/v1/alerts/${alertId}`) {
            expect(init?.method).toBe("GET");
            detailReads += 1;
            return jsonResponse(openDetail);
          }
          if (url === "/api/v1/alerts?limit=50") {
            expect(init?.method).toBe("GET");
            return queueResponse();
          }
          throw new Error(`Unexpected URL ${url}`);
        });
        const { unmount, source, onSessionExpired } = renderPanel();
        await openDetailScreen();
        fireEvent.click(
          screen.getByRole("button", { name: "Acknowledge alert" }),
        );
        expect(commandSignal).toBeInstanceOf(AbortSignal);
        expect(commandSignal?.aborted).toBe(false);

        if (navigation === "Back") {
          fireEvent.click(
            screen.getByRole("button", { name: "Back to alerts" }),
          );
          expect(commandSignal?.aborted).toBe(true);
          await openDetailScreen();
          expect(detailReads).toBe(2);
        } else {
          unmount();
          expect(commandSignal?.aborted).toBe(true);
          expect(source.close).toHaveBeenCalledOnce();
        }
        const callsAfterLeaving = fetchMock.mock.calls.length;
        await act(async () =>
          pending.resolve(
            outcome === "success"
              ? jsonResponse(acknowledgedDetail)
              : jsonResponse({}, Number(outcome)),
          ),
        );

        expect(fetchMock).toHaveBeenCalledTimes(callsAfterLeaving);
        expect(
          fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
        ).toHaveLength(1);
        expect(
          fetchMock.mock.calls.every(
            ([url, init]) =>
              init?.method ===
              (String(url) === `/api/v1/alerts/${alertId}/acknowledge`
                ? "POST"
                : "GET"),
          ),
        ).toBe(true);
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(screen.queryByText("Alert acknowledged.")).toBeNull();
        expect(
          screen.queryByText(/Recovering the latest saved state/),
        ).toBeNull();
        if (navigation === "Back") {
          expect(
            screen.getByRole("button", { name: "Acknowledge alert" }),
          ).toBeEnabled();
          expect(
            screen.queryByRole("button", { name: "Resolve alert" }),
          ).toBeNull();
          expect(source.close).not.toHaveBeenCalled();
        } else {
          expect(detailReads).toBe(1);
          expect(
            screen.queryByRole("heading", { name: "High bearing temperature" }),
          ).toBeNull();
        }
      },
    );
  });

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

  it.each(["503", "malformed"] as const)(
    "retains exactly one queued refresh after an active queue read returns %s",
    async (failure) => {
      const { source, factory } = streamHarness();
      const activeRead = deferred<Response>();
      const trailingRead = deferred<Response>();
      const onSessionExpired = vi.fn();
      let queueReads = 0;
      const fetchMock = installFetch(async () => {
        queueReads += 1;
        if (queueReads === 1) return queueResponse();
        if (queueReads === 2) return activeRead.promise;
        if (queueReads === 3) return trailingRead.promise;
        throw new Error("unexpected extra queue read");
      });
      render(
        <AlertPanel
          roleCode="VIEWER"
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
          createEventSource={factory}
        />,
      );
      await screen.findByText("1 occurrence");
      await act(async () => source.emit("open"));
      expect(queueReads).toBe(2);
      for (const changeType of ["OCCURRENCE_RECORDED", "STATUS_CHANGED"]) {
        await act(async () =>
          source.emit("alert-changed", { alertId, changeType }),
        );
      }
      expect(queueReads).toBe(2);

      await act(async () =>
        activeRead.resolve(
          failure === "503"
            ? jsonResponse({}, 503)
            : jsonResponse({ alerts: [{ id: alertId }], limit: 50 }),
        ),
      );
      await waitFor(() => expect(queueReads).toBe(3));
      expect(screen.getByText("1 occurrence")).toBeVisible();
      expect(
        screen.getByText(
          "Live refresh failed. The last confirmed alert queue is still shown.",
        ),
      ).toBeVisible();

      await act(async () =>
        trailingRead.resolve(
          queueResponse([
            {
              ...summary,
              occurrenceCount: refreshedOpenDetail.occurrenceCount,
              lastOccurredAt: refreshedOpenDetail.lastOccurredAt,
            },
          ]),
        ),
      );
      expect(await screen.findByText("2 occurrences")).toBeVisible();
      expect(screen.queryByText(/Live refresh failed/)).toBeNull();
      expect(queueReads).toBe(3);
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(source.close).not.toHaveBeenCalled();
      expect(
        fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
      ).toBe(true);
    },
  );

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

  it.each(["503", "malformed"] as const)(
    "retains exactly one queued refresh after an active detail read returns %s",
    async (failure) => {
      const { source, factory } = streamHarness();
      const activeRead = deferred<Response>();
      const trailingRead = deferred<Response>();
      const onSessionExpired = vi.fn();
      let detailReads = 0;
      const fetchMock = installFetch(async (url) => {
        if (url === `/api/v1/alerts/${alertId}`) {
          detailReads += 1;
          if (detailReads === 1) return jsonResponse(openDetail);
          if (detailReads === 2) return activeRead.promise;
          if (detailReads === 3) return trailingRead.promise;
          throw new Error("unexpected extra detail read");
        }
        return queueResponse();
      });
      render(
        <AlertPanel
          roleCode="VIEWER"
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
          createEventSource={factory}
        />,
      );
      fireEvent.click(
        await screen.findByRole("button", {
          name: /view open alert for boiler feed pump/i,
        }),
      );
      await screen.findByRole("heading", { name: "High bearing temperature" });
      await act(async () => source.emit("open"));
      expect(detailReads).toBe(2);
      for (const changeType of ["OCCURRENCE_RECORDED", "STATUS_CHANGED"]) {
        await act(async () =>
          source.emit("alert-changed", { alertId, changeType }),
        );
      }
      expect(detailReads).toBe(2);

      await act(async () =>
        activeRead.resolve(
          failure === "503"
            ? jsonResponse({}, 503)
            : jsonResponse({ id: alertId }),
        ),
      );
      await waitFor(() => expect(detailReads).toBe(3));
      expect(screen.getByText("1")).toBeVisible();
      expect(
        screen.getByText(
          "Live refresh failed. The last confirmed alert detail is still shown.",
        ),
      ).toBeVisible();

      await act(async () =>
        trailingRead.resolve(jsonResponse(refreshedOpenDetail)),
      );
      expect(await screen.findByText("2")).toBeVisible();
      expect(screen.queryByText(/Live refresh failed/)).toBeNull();
      expect(detailReads).toBe(3);
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(source.close).not.toHaveBeenCalled();
      expect(
        fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
      ).toBe(true);
    },
  );

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

  it.each(
    (["conflict", "uncertain"] as const).flatMap((reason) =>
      (["503", "malformed"] as const).flatMap((failure) =>
        (["success", "failure"] as const).map((trailingOutcome) => ({
          reason,
          failure,
          trailingOutcome,
        })),
      ),
    ),
  )(
    "keeps $reason recovery locked after $failure until its single trailing $trailingOutcome settles",
    async ({ reason, failure, trailingOutcome }) => {
      const { source, factory } = streamHarness();
      const recovery = deferred<Response>();
      const trailingRefresh = deferred<Response>();
      const retryRefresh = deferred<Response>();
      const onSessionExpired = vi.fn();
      let detailReads = 0;
      let commandCalls = 0;
      const fetchMock = installFetch(async (url, init) => {
        if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
          expect(init?.method).toBe("POST");
          commandCalls += 1;
          if (reason === "uncertain") {
            throw new TypeError("command response lost");
          }
          return jsonResponse({}, 409);
        }
        expect(init?.method).toBe("GET");
        if (url === `/api/v1/alerts/${alertId}`) {
          detailReads += 1;
          if (detailReads === 1) return jsonResponse(openDetail);
          if (detailReads === 2) return recovery.promise;
          if (detailReads === 3) return trailingRefresh.promise;
          if (detailReads === 4) return retryRefresh.promise;
          throw new Error("unexpected extra detail read");
        }
        if (url === "/api/v1/alerts?limit=50") return queueResponse();
        throw new Error(`Unexpected URL ${url}`);
      });

      render(
        <AlertPanel
          roleCode="OPERATIONS_ADMIN"
          csrfToken={csrfToken}
          onSessionExpired={onSessionExpired}
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
      const back = screen.getByRole("button", { name: "Back to alerts" });
      back.focus();

      await act(async () => source.emit("open"));
      for (const changeType of ["STATUS_CHANGED", "OCCURRENCE_RECORDED"]) {
        await act(async () =>
          source.emit("alert-changed", { alertId, changeType }),
        );
      }
      expect(detailReads).toBe(2);
      await act(async () =>
        recovery.resolve(
          failure === "503"
            ? jsonResponse({}, 503)
            : jsonResponse({ id: alertId }),
        ),
      );
      await waitFor(() => expect(detailReads).toBe(3));
      expect(screen.getByText("1")).toBeVisible();
      expect(
        screen.getByText(
          "Live refresh failed. The last confirmed alert detail is still shown.",
        ),
      ).toBeVisible();
      const recoveringCommand = screen.getByRole("button", {
        name: "Recovering latest state…",
      });
      expect(recoveringCommand).toBeDisabled();
      expect(
        screen.queryByRole("button", { name: "Retry latest state" }),
      ).toBeNull();
      fireEvent.click(recoveringCommand);
      expect(commandCalls).toBe(1);
      expect(back).toHaveFocus();

      await act(async () =>
        trailingRefresh.resolve(
          trailingOutcome === "success"
            ? jsonResponse(acknowledgedDetail)
            : jsonResponse({}, 503),
        ),
      );
      if (trailingOutcome === "failure") {
        const lockedCommand = await screen.findByRole("button", {
          name: "Latest state required",
        });
        expect(lockedCommand).toBeDisabled();
        expect(
          screen.getByText(/latest saved state could not be loaded/i),
        ).toBeVisible();
        expect(back).toHaveFocus();
        fireEvent.click(lockedCommand);
        expect(commandCalls).toBe(1);
        expect(detailReads).toBe(3);

        fireEvent.click(
          screen.getByRole("button", { name: "Retry latest state" }),
        );
        await waitFor(() => expect(detailReads).toBe(4));
        expect(
          screen.getByRole("button", { name: "Recovering latest state…" }),
        ).toBeDisabled();
        await act(async () =>
          retryRefresh.resolve(jsonResponse(acknowledgedDetail)),
        );
      } else {
        expect(back).toHaveFocus();
      }

      expect(
        await screen.findByRole("button", { name: "Resolve alert" }),
      ).toBeEnabled();
      expect(
        screen.getByText(/latest saved state is now loaded/i),
      ).toBeVisible();
      expect(screen.queryByText(/Live refresh failed/)).toBeNull();
      expect(detailReads).toBe(trailingOutcome === "success" ? 3 : 4);
      expect(commandCalls).toBe(1);
      expect(
        fetchMock.mock.calls.filter(([, init]) => init?.method === "POST"),
      ).toHaveLength(1);
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(source.close).not.toHaveBeenCalled();
    },
  );

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

  it.each(["conflict", "uncertain"] as const)(
    "preserves deliberately moved Back focus when automatic %s recovery resolves the alert",
    async (reason) => {
      const { factory } = streamHarness();
      const recovery = deferred<Response>();
      let detailReads = 0;
      let queueReads = 0;
      let commandCalls = 0;
      installFetch(async (url, init) => {
        if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
          expect(init?.method).toBe("POST");
          commandCalls += 1;
          if (reason === "uncertain") {
            throw new TypeError("response lost");
          }
          return jsonResponse({}, 409);
        }
        if (url === `/api/v1/alerts/${alertId}`) {
          detailReads += 1;
          return detailReads === 1
            ? jsonResponse(openDetail)
            : recovery.promise;
        }
        expect(url).toBe("/api/v1/alerts?limit=50");
        queueReads += 1;
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
      const command = await screen.findByRole("button", {
        name: "Acknowledge alert",
      });
      command.focus();
      fireEvent.click(command);
      await waitFor(() => expect(detailReads).toBe(2));
      await waitFor(() => expect(queueReads).toBe(2));
      expect(
        screen.getByRole("button", { name: "Recovering latest state…" }),
      ).toBeDisabled();
      const back = screen.getByRole("button", { name: "Back to alerts" });
      back.focus();
      expect(back).toHaveFocus();

      await act(async () => recovery.resolve(jsonResponse(resolvedDetail)));
      expect(
        await screen.findByText(/latest saved state is now loaded/i),
      ).toBeVisible();
      expect(
        screen.queryByRole("button", { name: "Resolve alert" }),
      ).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Acknowledge alert" }),
      ).toBeNull();
      expect(detailReads).toBe(2);
      expect(queueReads).toBe(2);
      expect(commandCalls).toBe(1);
      expect(back).toHaveFocus();
    },
  );

  it("keeps stable detail focus from command submission through automatic recovery resolving the alert", async () => {
    const { factory } = streamHarness();
    const commandResponse = deferred<Response>();
    const recovery = deferred<Response>();
    let detailReads = 0;
    let commandCalls = 0;
    installFetch(async (url) => {
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        commandCalls += 1;
        return commandResponse.promise;
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
    const command = await screen.findByRole("button", {
      name: "Acknowledge alert",
    });
    const detail = screen.getByRole("region", {
      name: "High bearing temperature",
    });
    command.focus();
    fireEvent.click(command);
    expect(
      screen.getByRole("button", { name: "Saving alert…" }),
    ).toBeDisabled();
    expect(detail).toHaveFocus();
    expect(commandCalls).toBe(1);
    expect(detailReads).toBe(1);

    await act(async () => commandResponse.resolve(jsonResponse({}, 409)));
    await waitFor(() => expect(detailReads).toBe(2));
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();
    expect(detail).toHaveFocus();

    await act(async () => recovery.resolve(jsonResponse(resolvedDetail)));
    const feedback = await screen.findByText(
      /latest saved state is now loaded/i,
    );
    expect(feedback).toBeVisible();
    expect(detail).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Resolve alert" })).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Acknowledge alert" }),
    ).toBeNull();
    expect(detailReads).toBe(2);
    expect(commandCalls).toBe(1);
  });

  it.each(["conflict", "uncertain"] as const)(
    "preserves deliberately moved Create work order focus when explicit %s recovery retry resolves the alert",
    async (reason) => {
      const { factory } = streamHarness();
      const recovery = deferred<Response>();
      let detailReads = 0;
      let queueReads = 0;
      let commandCalls = 0;
      installFetch(async (url, init) => {
        if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
          expect(init?.method).toBe("POST");
          commandCalls += 1;
          if (reason === "uncertain") {
            throw new TypeError("response lost");
          }
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
          return recovery.promise;
        }
        expect(url).toBe("/api/v1/alerts?limit=50");
        queueReads += 1;
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
      const command = await screen.findByRole("button", {
        name: "Acknowledge alert",
      });
      command.focus();
      fireEvent.click(command);
      const retry = await screen.findByRole("button", {
        name: "Retry latest state",
      });
      expect(
        screen.getByRole("button", { name: "Latest state required" }),
      ).toBeDisabled();
      retry.focus();
      fireEvent.click(retry);
      const feedback = await screen.findByText(
        "Retrying the latest saved alert state…",
      );
      expect(feedback).toHaveFocus();
      await waitFor(() => expect(detailReads).toBe(3));
      await waitFor(() => expect(queueReads).toBe(2));
      expect(
        screen.getByRole("button", { name: "Recovering latest state…" }),
      ).toBeDisabled();
      expect(
        screen.queryByRole("button", { name: "Retry latest state" }),
      ).toBeNull();
      const create = screen.getByRole("button", { name: "Create work order" });
      create.focus();
      expect(create).toHaveFocus();

      await act(async () => recovery.resolve(jsonResponse(resolvedDetail)));
      expect(
        await screen.findByText(/latest saved state is now loaded/i),
      ).toBeVisible();
      expect(
        screen.queryByRole("button", { name: "Resolve alert" }),
      ).toBeNull();
      expect(
        screen.queryByRole("button", { name: "Acknowledge alert" }),
      ).toBeNull();
      expect(detailReads).toBe(3);
      expect(queueReads).toBe(2);
      expect(commandCalls).toBe(1);
      expect(create).toHaveFocus();
    },
  );

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
    const back = screen.getByRole("button", { name: "Back to alerts" });
    back.focus();
    expect(back).toHaveFocus();
    await act(async () =>
      ordinaryRefresh.resolve(jsonResponse(resolvedDetail)),
    );
    expect(
      screen.getByRole("button", { name: "Recovering latest state…" }),
    ).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Resolve alert" })).toBeNull();
    expect(screen.queryByText(/latest saved state is now loaded/i)).toBeNull();
    expect(back).toHaveFocus();
    expect(commandCalls).toBe(1);

    await act(async () =>
      retryRefresh.resolve(jsonResponse(acknowledgedDetail)),
    );
    expect(
      await screen.findByRole("button", { name: "Resolve alert" }),
    ).toBeEnabled();
    expect(back).toHaveFocus();
    expect(detailReads).toBe(4);
    expect(commandCalls).toBe(1);
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

  describe("ALR-03: ready alert detail denial focus", () => {
    it.each(["Create work order", "Back to alerts"] as const)(
      "preserves an appropriate target after focus moves to %s before a command returns 404",
      async (target) => {
        const { factory } = streamHarness();
        const commandResponse = deferred<Response>();
        const onSessionExpired = vi.fn();
        let commandCalls = 0;
        let detailReads = 0;
        let queueReads = 0;
        installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
            expect(init?.method).toBe("POST");
            commandCalls += 1;
            return commandResponse.promise;
          }
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return jsonResponse(openDetail);
          }
          expect(url).toBe("/api/v1/alerts?limit=50");
          queueReads += 1;
          return queueResponse();
        });
        render(
          <AlertPanel
            roleCode="OPERATIONS_ADMIN"
            csrfToken={csrfToken}
            onSessionExpired={onSessionExpired}
            createEventSource={factory}
          />,
        );
        fireEvent.click(
          await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          }),
        );
        const command = await screen.findByRole("button", {
          name: "Acknowledge alert",
        });
        const detail = screen.getByRole("region", {
          name: "High bearing temperature",
        });
        command.focus();
        fireEvent.click(command);
        expect(
          screen.getByRole("button", { name: "Saving alert…" }),
        ).toBeDisabled();
        const movedTarget = screen.getByRole("button", { name: target });
        movedTarget.focus();

        await act(async () =>
          commandResponse.resolve(
            jsonResponse({ detail: "Riverside secret compressor" }, 404),
          ),
        );
        expect(
          screen.getByRole("heading", { name: "Alert not found" }),
        ).toBeVisible();
        expect(
          target === "Back to alerts" ? movedTarget : detail,
        ).toHaveFocus();
        expect(screen.queryByText(/Riverside|compressor/)).toBeNull();
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(commandCalls).toBe(1);
        expect(detailReads).toBe(1);
        expect(queueReads).toBe(2);
      },
    );

    it.each(
      (["Acknowledge alert", "Create work order"] as const).flatMap((target) =>
        (["403", "404", "503", "malformed"] as const).map((outcome) => ({
          target,
          outcome,
        })),
      ),
    )(
      "preserves focus when a refresh of ready detail finishes with $outcome while $target is focused",
      async ({ target, outcome }) => {
        const { source, factory } = streamHarness();
        const refreshResponse = deferred<Response>();
        const onSessionExpired = vi.fn();
        let detailReads = 0;
        let queueReads = 0;
        const fetchMock = installFetch(async (url) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return detailReads === 1
              ? jsonResponse(openDetail)
              : refreshResponse.promise;
          }
          expect(url).toBe("/api/v1/alerts?limit=50");
          queueReads += 1;
          return queueResponse();
        });
        render(
          <AlertPanel
            roleCode="OPERATIONS_ADMIN"
            csrfToken={csrfToken}
            onSessionExpired={onSessionExpired}
            createEventSource={factory}
          />,
        );
        fireEvent.click(
          await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          }),
        );
        const control = await screen.findByRole("button", { name: target });
        const detail = screen.getByRole("region", {
          name: "High bearing temperature",
        });
        control.focus();
        await act(async () =>
          source.emit("alert-changed", {
            alertId,
            changeType: "STATUS_CHANGED",
          }),
        );
        expect(detailReads).toBe(2);
        expect(control).toHaveFocus();

        await act(async () =>
          refreshResponse.resolve(
            outcome === "malformed"
              ? jsonResponse({ id: alertId })
              : jsonResponse(
                  { detail: "Riverside secret compressor" },
                  Number(outcome),
                ),
          ),
        );

        if (outcome === "403" || outcome === "404") {
          expect(
            screen.getByRole("heading", {
              name:
                outcome === "403" ? "Alert access denied" : "Alert not found",
            }),
          ).toBeVisible();
          expect(screen.queryByRole("button", { name: target })).toBeNull();
          expect(detail).toHaveFocus();
        } else {
          expect(
            screen.getByText(
              "Live refresh failed. The last confirmed alert detail is still shown.",
            ),
          ).toBeVisible();
          expect(screen.getByRole("button", { name: target })).toBe(control);
          expect(control).toBeEnabled();
          expect(control).toHaveFocus();
        }
        expect(screen.queryByText(/Riverside|compressor/)).toBeNull();
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(detailReads).toBe(2);
        expect(queueReads).toBe(2);
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each([403, 404])(
      "preserves deliberately moved Back focus when ready detail receives %s",
      async (status) => {
        const { source, factory } = streamHarness();
        const refreshResponse = deferred<Response>();
        let detailReads = 0;
        const fetchMock = installFetch(async (url) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return detailReads === 1
              ? jsonResponse(openDetail)
              : refreshResponse.promise;
          }
          expect(url).toBe("/api/v1/alerts?limit=50");
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
        const control = await screen.findByRole("button", {
          name: "Create work order",
        });
        control.focus();
        await act(async () =>
          source.emit("alert-changed", {
            alertId,
            changeType: "STATUS_CHANGED",
          }),
        );
        expect(detailReads).toBe(2);
        const back = screen.getByRole("button", { name: "Back to alerts" });
        back.focus();

        await act(async () =>
          refreshResponse.resolve(jsonResponse({}, status)),
        );
        expect(
          screen.getByRole("heading", {
            name: status === 403 ? "Alert access denied" : "Alert not found",
          }),
        ).toBeVisible();
        expect(back).toHaveFocus();
        expect(detailReads).toBe(2);
        expect(fetchMock).toHaveBeenCalledTimes(4);
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(
      (
        [
          "command feedback",
          "command retry",
          "creation feedback",
          "creation retry",
        ] as const
      ).flatMap((target) =>
        ([403, 404] as const).map((status) => ({ target, status })),
      ),
    )(
      "moves focused $target to stable detail when a read returns $status",
      async ({ target, status }) => {
        const { source, factory } = streamHarness();
        const refreshResponse = deferred<Response>();
        const onSessionExpired = vi.fn();
        let detailReads = 0;
        let queueReads = 0;
        let workOrderReads = 0;
        let commandCalls = 0;
        let createCalls = 0;
        installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
            expect(init?.method).toBe("POST");
            commandCalls += 1;
            return target === "command feedback"
              ? jsonResponse(acknowledgedDetail)
              : jsonResponse({}, 409);
          }
          if (url === "/api/v1/work-orders") {
            expect(init?.method).toBe("POST");
            createCalls += 1;
            throw new TypeError("response lost");
          }
          if (url === "/api/v1/work-orders?limit=50") {
            expect(init?.method).toBe("GET");
            workOrderReads += 1;
            return jsonResponse({}, 503);
          }
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            if (detailReads === 1) {
              return jsonResponse(openDetail);
            }
            return target === "command retry" && detailReads === 2
              ? jsonResponse({}, 503)
              : refreshResponse.promise;
          }
          expect(url).toBe("/api/v1/alerts?limit=50");
          queueReads += 1;
          return queueResponse();
        });
        render(
          <AlertPanel
            roleCode="OPERATIONS_ADMIN"
            csrfToken={csrfToken}
            onSessionExpired={onSessionExpired}
            createEventSource={factory}
          />,
        );
        fireEvent.click(
          await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          }),
        );
        const command = await screen.findByRole("button", {
          name: "Acknowledge alert",
        });
        const detail = screen.getByRole("region", {
          name: "High bearing temperature",
        });
        let focusedTarget: HTMLElement;
        if (target === "command feedback" || target === "command retry") {
          command.focus();
          fireEvent.click(command);
          if (target === "command feedback") {
            focusedTarget = await screen.findByText("Alert acknowledged.");
            await waitFor(() => expect(focusedTarget).toHaveFocus());
          } else {
            focusedTarget = await screen.findByRole("button", {
              name: "Retry latest state",
            });
            focusedTarget.focus();
          }
          await waitFor(() => expect(queueReads).toBe(2));
        } else {
          fireEvent.click(
            screen.getByRole("button", { name: "Create work order" }),
          );
          const retry = await screen.findByRole("button", {
            name: "Retry authoritative check",
          });
          retry.focus();
          if (target === "creation feedback") {
            fireEvent.click(retry);
            focusedTarget = await screen.findByText(
              "The create result and saved work-order queue could not be confirmed. Retry authoritative recovery before creating again.",
            );
            expect(workOrderReads).toBe(2);
          } else {
            focusedTarget = retry;
          }
        }
        expect(focusedTarget).toHaveFocus();
        await act(async () =>
          source.emit("alert-changed", {
            alertId,
            changeType: "STATUS_CHANGED",
          }),
        );
        expect(focusedTarget).toHaveFocus();

        await act(async () =>
          refreshResponse.resolve(
            jsonResponse({ detail: "Riverside secret compressor" }, status),
          ),
        );
        expect(
          screen.getByRole("heading", {
            name: status === 403 ? "Alert access denied" : "Alert not found",
          }),
        ).toBeVisible();
        expect(focusedTarget).not.toBeInTheDocument();
        expect(detail).toHaveFocus();
        expect(screen.queryByText(/Riverside|compressor/)).toBeNull();
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(commandCalls).toBe(target.startsWith("command") ? 1 : 0);
        expect(createCalls).toBe(target.startsWith("creation") ? 1 : 0);
        expect(detailReads).toBe(target === "command retry" ? 3 : 2);
        expect(queueReads).toBe(target.startsWith("command") ? 3 : 2);
        expect(workOrderReads).toBe(
          target === "creation feedback"
            ? 2
            : target === "creation retry"
              ? 1
              : 0,
        );
      },
    );
  });

  describe("ALR-03: unavailable alert detail lifecycle", () => {
    const initialFailures = ["503", "malformed"] as const;

    function initialFailure(reason: (typeof initialFailures)[number]) {
      return reason === "503"
        ? jsonResponse({}, 503)
        : jsonResponse({ id: alertId });
    }

    it.each(initialFailures)(
      "keeps stable detail focus when a manual retry after %s removes its button",
      async (reason) => {
        const { factory } = streamHarness();
        const retryResponse = deferred<Response>();
        let detailReads = 0;
        let queueReads = 0;
        const fetchMock = installFetch(async (url) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return detailReads === 1
              ? initialFailure(reason)
              : retryResponse.promise;
          }
          queueReads += 1;
          return queueResponse();
        });
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
        const retry = await screen.findByRole("button", {
          name: "Retry alert details",
        });
        const detail = screen.getByRole("region", {
          name: "Alert details unavailable",
        });
        retry.focus();
        fireEvent.click(retry);

        expect(screen.getByText("Loading alert details…")).toBeVisible();
        expect(
          screen.queryByRole("button", { name: "Retry alert details" }),
        ).toBeNull();
        expect(detail).toHaveFocus();
        expect(detailReads).toBe(2);

        await act(async () => retryResponse.resolve(jsonResponse(openDetail)));
        expect(
          await screen.findByRole("heading", {
            name: "High bearing temperature",
          }),
        ).toBeVisible();
        expect(detail).toHaveFocus();
        expect(detailReads).toBe(2);
        expect(queueReads).toBe(1);
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(
      initialFailures.flatMap((reason) =>
        (["success", "403", "404", "503"] as const).map((outcome) => ({
          reason,
          outcome,
        })),
      ),
    )(
      "preserves an appropriate focus target when a stream refresh after $reason finishes with $outcome",
      async ({ reason, outcome }) => {
        const { source, factory } = streamHarness();
        const refreshResponse = deferred<Response>();
        let detailReads = 0;
        let queueReads = 0;
        const onSessionExpired = vi.fn();
        const fetchMock = installFetch(async (url) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return detailReads === 1
              ? initialFailure(reason)
              : refreshResponse.promise;
          }
          queueReads += 1;
          return queueResponse();
        });
        render(
          <AlertPanel
            roleCode="VIEWER"
            csrfToken={csrfToken}
            onSessionExpired={onSessionExpired}
            createEventSource={factory}
          />,
        );
        fireEvent.click(
          await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          }),
        );
        const retry = await screen.findByRole("button", {
          name: "Retry alert details",
        });
        const detail = screen.getByRole("region", {
          name: "Alert details unavailable",
        });
        retry.focus();
        await act(async () => source.emit("open"));
        expect(detailReads).toBe(2);
        expect(retry).toHaveFocus();
        expect(retry).toBeVisible();

        await act(async () =>
          refreshResponse.resolve(
            outcome === "success"
              ? jsonResponse(openDetail)
              : jsonResponse(
                  { detail: "Riverside secret compressor" },
                  Number(outcome),
                ),
          ),
        );

        if (outcome === "503") {
          expect(
            screen.getByRole("button", { name: "Retry alert details" }),
          ).toBe(retry);
          expect(retry).toHaveFocus();
          expect(
            screen.getByRole("heading", { name: "Alert details unavailable" }),
          ).toBeVisible();
        } else {
          expect(
            screen.queryByRole("button", { name: "Retry alert details" }),
          ).toBeNull();
          expect(detail).toHaveFocus();
          expect(
            screen.getByRole("heading", {
              name:
                outcome === "success"
                  ? "High bearing temperature"
                  : outcome === "403"
                    ? "Alert access denied"
                    : "Alert not found",
            }),
          ).toBeVisible();
        }
        expect(screen.queryByText(/Riverside|compressor/)).toBeNull();
        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(detailReads).toBe(2);
        expect(queueReads).toBe(2);
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(
      initialFailures.flatMap((reason) =>
        (["manual", "stream"] as const).map((refresh) => ({ reason, refresh })),
      ),
    )(
      "preserves moved Back focus while a $refresh retry after $reason completes",
      async ({ reason, refresh }) => {
        const { source, factory } = streamHarness();
        const refreshResponse = deferred<Response>();
        let detailReads = 0;
        let queueReads = 0;
        const fetchMock = installFetch(async (url) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            return detailReads === 1
              ? initialFailure(reason)
              : refreshResponse.promise;
          }
          queueReads += 1;
          return queueResponse();
        });
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
        const retry = await screen.findByRole("button", {
          name: "Retry alert details",
        });
        retry.focus();
        if (refresh === "manual") {
          fireEvent.click(retry);
        } else {
          await act(async () => source.emit("open"));
        }
        expect(detailReads).toBe(2);
        const back = screen.getByRole("button", { name: "Back to alerts" });
        back.focus();
        expect(back).toHaveFocus();

        await act(async () =>
          refreshResponse.resolve(jsonResponse(openDetail)),
        );
        expect(
          await screen.findByRole("heading", {
            name: "High bearing temperature",
          }),
        ).toBeVisible();
        expect(back).toHaveFocus();
        expect(detailReads).toBe(2);
        expect(queueReads).toBe(refresh === "manual" ? 1 : 2);
        expect(
          fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
        ).toBe(true);
      },
    );

    it.each(["Back", "unmount"] as const)(
      "aborts detail on %s and ignores its late 401 without another request",
      async (navigation) => {
        const { source, factory } = streamHarness();
        const detailResponse = deferred<Response>();
        const onSessionExpired = vi.fn();
        let detailSignal: AbortSignal | null | undefined;
        let queueReads = 0;
        let detailReads = 0;
        const fetchMock = installFetch(async (url, init) => {
          if (url === `/api/v1/alerts/${alertId}`) {
            detailReads += 1;
            detailSignal = init?.signal;
            return detailResponse.promise;
          }
          queueReads += 1;
          return queueResponse();
        });
        const { unmount } = render(
          <AlertPanel
            roleCode="VIEWER"
            csrfToken={csrfToken}
            onSessionExpired={onSessionExpired}
            createEventSource={factory}
          />,
        );
        fireEvent.click(
          await screen.findByRole("button", {
            name: /view open alert for boiler feed pump/i,
          }),
        );
        expect(screen.getByText("Loading alert details…")).toBeVisible();
        expect(detailSignal).toBeInstanceOf(AbortSignal);
        expect(detailSignal?.aborted).toBe(false);

        if (navigation === "Back") {
          fireEvent.click(
            screen.getByRole("button", { name: "Back to alerts" }),
          );
        } else {
          unmount();
        }
        expect(detailSignal?.aborted).toBe(true);
        await act(async () => detailResponse.resolve(jsonResponse({}, 401)));

        expect(onSessionExpired).not.toHaveBeenCalled();
        expect(detailReads).toBe(1);
        expect(queueReads).toBe(1);
        expect(fetchMock).toHaveBeenCalledTimes(2);
        if (navigation === "Back") {
          expect(
            screen.getByRole("button", {
              name: /view open alert for boiler feed pump/i,
            }),
          ).toHaveFocus();
          expect(source.close).not.toHaveBeenCalled();
        } else {
          expect(source.close).toHaveBeenCalledOnce();
          expect(screen.queryByRole("heading", { name: "Alerts" })).toBeNull();
        }
      },
    );
  });
});
