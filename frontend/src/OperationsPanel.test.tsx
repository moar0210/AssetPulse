import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { OperationsPanel } from "./OperationsPanel";

const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
} as const;

const eventId = "60000000-0000-0000-0000-000000000001";
const deadEvent = {
  id: eventId,
  telemetryBatchId: "70000000-0000-0000-0000-000000000001",
  eventType: "TELEMETRY_BATCH_ACCEPTED",
  attemptCount: 5,
  createdAt: "2026-08-23T10:00:00Z",
  deadAt: "2026-08-23T10:05:00Z",
  updatedAt: "2026-08-23T10:05:01Z",
  lastErrorCode: "PROCESSING_FAILED",
  lastErrorMessage: "Processing failed; another attempt may be scheduled.",
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
      instance: `/api/v1/processing-events/${eventId}/retry`,
      code,
      correlationId: "correlation-id",
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

function deadQueue(events: readonly unknown[] = [deadEvent]) {
  return jsonResponse({ events, limit: 50 });
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

async function openEventDetail() {
  fireEvent.click(
    await screen.findByRole("button", {
      name: `View dead processing event ${eventId}`,
    }),
  );
  return screen.findByRole("heading", { name: "Processing event" });
}

describe("OperationsPanel", () => {
  it("announces loading, then exposes only the bounded safe event details", async () => {
    const queue = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockReturnValue(queue.promise),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading dead processing events",
    );
    await act(async () => queue.resolve(deadQueue()));
    await openEventDetail();

    expect(screen.getByText(eventId)).toBeVisible();
    expect(screen.getByText(deadEvent.telemetryBatchId)).toBeVisible();
    expect(screen.getByText("Telemetry batch accepted")).toBeVisible();
    expect(screen.getByText("5 of 5")).toBeVisible();
    expect(screen.getByText("PROCESSING_FAILED")).toBeVisible();
    expect(screen.getByText(deadEvent.lastErrorMessage)).toBeVisible();
    expect(
      screen.queryByText(/organisation|claim owner|stack trace/i),
    ).toBeNull();
  });

  it("renders an explicit empty queue", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(deadQueue([])),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );

    expect(
      await screen.findByText("No dead processing events are awaiting review."),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Refresh operations" }),
    ).toBeEnabled();
  });

  it("shows an unavailable state and retries the queue", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 503))
      .mockResolvedValueOnce(deadQueue([]));
    vi.stubGlobal("fetch", fetchMock);

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load processing operations",
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry operations" }));

    expect(
      await screen.findByText("No dead processing events are awaiting review."),
    ).toBeVisible();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("shows the server-authoritative forbidden state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 403)),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );

    expect(
      await screen.findByRole("heading", { name: "Operations access denied" }),
    ).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "server did not grant access",
    );
    expect(
      screen.queryByRole("button", { name: /processing event/i }),
    ).toBeNull();
  });

  it("locks a pending bodyless retry, preserves success, and ignores a stale pre-command read", async () => {
    const staleRefresh = deferred<Response>();
    const retryResponse = deferred<Response>();
    let queueReads = 0;
    const staleSignal: { current: AbortSignal | null } = { current: null };
    const fetchMock = vi.fn<typeof fetch>((input, init) => {
      const url = String(input);
      if (url === "/api/v1/processing-events/dead?limit=50") {
        queueReads += 1;
        if (queueReads === 1) {
          return Promise.resolve(deadQueue());
        }
        if (queueReads === 2) {
          staleSignal.current = init?.signal ?? null;
          return staleRefresh.promise;
        }
        return Promise.resolve(deadQueue([]));
      }
      if (url === `/api/v1/processing-events/${eventId}/retry`) {
        return retryResponse.promise;
      }
      return Promise.resolve(jsonResponse({}, 404));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );

    const row = await screen.findByRole("button", {
      name: `View dead processing event ${eventId}`,
    });
    fireEvent.click(screen.getByRole("button", { name: "Refresh operations" }));
    fireEvent.click(row);
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry processing event" }),
    );

    expect(
      screen.getByRole("button", { name: "Requesting retry…" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Back to operations" }),
    ).toBeDisabled();
    expect(staleSignal.current?.aborted).toBe(true);

    await act(async () =>
      retryResponse.resolve(
        new Response(null, {
          status: 204,
          headers: { "Cache-Control": "no-store" },
        }),
      ),
    );

    const success = await screen.findByText(
      "Retry accepted. Processing will resume asynchronously with a fresh attempt cycle.",
    );
    expect(success).toHaveFocus();
    expect(screen.getByText("No longer dead")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Retry processing event" }),
    ).toBeNull();
    const commandCall = fetchMock.mock.calls.find(
      ([url]) => String(url) === `/api/v1/processing-events/${eventId}/retry`,
    );
    expect(commandCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-value" }),
    );
    expect(commandCall?.[1]?.body).toBeUndefined();

    await act(async () => staleRefresh.resolve(deadQueue()));
    fireEvent.click(screen.getByRole("button", { name: "Back to operations" }));
    expect(
      await screen.findByText("No dead processing events are awaiting review."),
    ).toBeVisible();
    expect(
      screen.getByRole("region", { name: "Processing operations" }),
    ).toHaveFocus();
  });

  it("keeps a confirmed retry out of the queue when the follow-up refresh fails", async () => {
    let queueReads = 0;
    const followUpRefresh = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/processing-events/dead?limit=50") {
          queueReads += 1;
          if (queueReads === 1) {
            return Promise.resolve(deadQueue());
          }
          if (queueReads === 2) {
            return Promise.resolve(jsonResponse({}, 503));
          }
          return followUpRefresh.promise;
        }
        if (url === `/api/v1/processing-events/${eventId}/retry`) {
          return Promise.resolve(new Response(null, { status: 204 }));
        }
        return Promise.resolve(jsonResponse({}, 404));
      }),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    const row = await screen.findByRole("button", {
      name: `View dead processing event ${eventId}`,
    });
    fireEvent.click(screen.getByRole("button", { name: "Refresh operations" }));
    expect(await screen.findByText(/refresh failed/i)).toBeVisible();
    fireEvent.click(row);
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );

    expect(
      await screen.findByText(
        "Retry accepted. Processing will resume asynchronously with a fresh attempt cycle.",
      ),
    ).toBeVisible();
    expect(
      screen.getByText(/operations queue could not be refreshed/i),
    ).toBeVisible();

    await act(async () => followUpRefresh.resolve(jsonResponse({}, 503)));

    fireEvent.click(screen.getByRole("button", { name: "Back to operations" }));
    expect(
      await screen.findByText("No dead processing events are awaiting review."),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", {
        name: `View dead processing event ${eventId}`,
      }),
    ).toBeNull();
    expect(queueReads).toBe(3);
  });

  it("removes retry controls after a server-authoritative command denial", async () => {
    let retryCalls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/processing-events/dead?limit=50") {
          return Promise.resolve(deadQueue());
        }
        if (url === `/api/v1/processing-events/${eventId}/retry`) {
          retryCalls += 1;
          return Promise.resolve(problemResponse("ACCESS_DENIED", 403));
        }
        return Promise.resolve(jsonResponse({}, 404));
      }),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );

    expect(
      await screen.findByRole("heading", { name: "Operations access denied" }),
    ).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Retry processing event" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", {
        name: `View dead processing event ${eventId}`,
      }),
    ).toBeNull();
    expect(retryCalls).toBe(1);
  });

  it("keeps a conflict locked until an authoritative read removes the event", async () => {
    let queueReads = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/processing-events/dead?limit=50") {
          queueReads += 1;
          return Promise.resolve(
            queueReads === 1 ? deadQueue() : deadQueue([]),
          );
        }
        return Promise.resolve(
          problemResponse("PROCESSING_EVENT_STATE_CONFLICT", 409),
        );
      }),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );

    const feedback = await screen.findByText(
      "This event changed before the retry was accepted. The latest saved state confirms it is no longer dead.",
    );
    expect(feedback).toHaveFocus();
    expect(screen.getByText("No longer dead")).toBeVisible();
    expect(queueReads).toBe(2);
  });

  it("uses an authoritative read after an uncertain transport result before unlocking retry", async () => {
    let queueReads = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/processing-events/dead?limit=50") {
          queueReads += 1;
          return Promise.resolve(deadQueue());
        }
        return Promise.reject(new TypeError("connection lost"));
      }),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );

    expect(
      await screen.findByText(
        "The retry result could not be confirmed. The latest saved state still shows this event as dead.",
      ),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Retry processing event" }),
    ).toBeEnabled();
    expect(queueReads).toBe(2);
  });

  it("retains a manual authoritative recovery when the automatic read fails", async () => {
    let queueReads = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        const url = String(input);
        if (url === "/api/v1/processing-events/dead?limit=50") {
          queueReads += 1;
          if (queueReads === 1) {
            return Promise.resolve(deadQueue());
          }
          if (queueReads === 2) {
            return Promise.resolve(jsonResponse({}, 503));
          }
          return Promise.resolve(deadQueue([]));
        }
        return Promise.resolve(
          problemResponse("PROCESSING_EVENT_STATE_CONFLICT", 409),
        );
      }),
    );

    render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );

    expect(
      await screen.findByText(/latest saved state could not be loaded/i),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Latest state required" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Back to operations" }),
    ).toBeDisabled();

    const recoveryButton = screen.getByRole("button", {
      name: "Retry latest state",
    });
    recoveryButton.focus();
    fireEvent.click(recoveryButton);

    const recovered = await screen.findByText(
      "This event changed before the retry was accepted. The latest saved state confirms it is no longer dead.",
    );
    expect(recovered).toHaveFocus();
    expect(
      screen.getByRole("button", { name: "Back to operations" }),
    ).toBeEnabled();
    expect(queueReads).toBe(3);
  });

  it("delegates expired sessions and rejected request verification to App", async () => {
    const onSessionExpired = vi.fn();
    let retryCount = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input) => {
        if (String(input).endsWith("/retry")) {
          retryCount += 1;
          return Promise.resolve(
            retryCount === 1
              ? jsonResponse({}, 401)
              : problemResponse("CSRF_REJECTED", 403),
          );
        }
        return Promise.resolve(deadQueue());
      }),
    );

    const { unmount } = render(
      <OperationsPanel
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
      />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());

    unmount();
    render(
      <OperationsPanel
        csrfToken={csrfToken}
        onSessionExpired={onSessionExpired}
      />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );
    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledTimes(2));
  });

  it("aborts an in-flight retry when the panel unmounts", async () => {
    const commandSignal: { current: AbortSignal | null } = { current: null };
    const command = deferred<Response>();
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>((input, init) => {
        if (String(input).endsWith("/retry")) {
          commandSignal.current = init?.signal ?? null;
          return command.promise;
        }
        return Promise.resolve(deadQueue());
      }),
    );

    const { unmount } = render(
      <OperationsPanel csrfToken={csrfToken} onSessionExpired={vi.fn()} />,
    );
    await openEventDetail();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );
    await waitFor(() => expect(commandSignal.current).not.toBeNull());

    unmount();
    expect(commandSignal.current?.aborted).toBe(true);
  });
});
