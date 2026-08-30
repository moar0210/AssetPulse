import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AuditPanel } from "./AuditPanel";

const auditEvent = {
  id: "a0000000-0000-0000-0000-000000000001",
  actor: {
    id: "10000000-0000-0000-0000-000000000001",
    displayName: "Nora Admin",
  },
  action: "ALERT_ACKNOWLEDGED",
  subject: { type: "ALERT", id: "50000000-0000-0000-0000-000000000001" },
  occurredAt: "2026-08-28T10:00:00.123456Z",
  correlationId: "b0000000-0000-0000-0000-000000000001",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function auditResponse(events: readonly unknown[] = [auditEvent]) {
  return jsonResponse({ events, limit: 50 });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

describe("AUD-01 AuditPanel", () => {
  it("announces loading and shows a bounded read-only table with actor, action, subject, time, and correlation", async () => {
    const request = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>().mockReturnValue(request.promise);
    vi.stubGlobal("fetch", fetchMock);
    render(<AuditPanel onSessionExpired={vi.fn()} />);

    expect(screen.getByRole("status")).toHaveTextContent("Loading audit trail");
    expect(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    ).toBeDisabled();
    await act(async () => request.resolve(auditResponse()));

    const table = screen.getByRole("table", {
      name: "Latest organisation audit events (up to 50)",
    });
    const row = within(table).getByRole("row", { name: /Nora Admin/ });
    expect(within(row).getByText(auditEvent.actor.id)).toBeVisible();
    expect(within(row).getByText("Alert acknowledged")).toBeVisible();
    expect(within(row).getByText("Alert")).toBeVisible();
    expect(within(row).getByText(auditEvent.subject.id)).toBeVisible();
    expect(within(row).getByText(auditEvent.correlationId)).toBeVisible();
    expect(row.querySelector("time")).toHaveAttribute(
      "datetime",
      auditEvent.occurredAt,
    );
    expect(
      within(table).getByRole("columnheader", { name: "Time (UTC)" }),
    ).toBeVisible();
    expect(screen.getByLabelText("Loaded audit event count")).toHaveTextContent(
      "1",
    );
    expect(
      screen.getByRole("region", {
        name: "Latest organisation audit events (up to 50)",
      }),
    ).toHaveAttribute("tabindex", "0");
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(
      fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
    ).toBe(true);
  });

  it("shows an explicit empty audit trail", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(auditResponse([])),
    );
    render(<AuditPanel onSessionExpired={vi.fn()} />);
    expect(
      await screen.findByText(
        "No audit events have been recorded for your organisation.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("table")).toBeNull();
    expect(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    ).toBeEnabled();
  });

  it("retries unavailable audit reads without a mutation", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 503))
      .mockResolvedValueOnce(auditResponse());
    vi.stubGlobal("fetch", fetchMock);
    render(<AuditPanel onSessionExpired={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load the audit trail",
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry audit trail" }));
    expect(await screen.findByText("Alert acknowledged")).toBeVisible();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(
      fetchMock.mock.calls.every(([, init]) => init?.method === "GET"),
    ).toBe(true);
  });

  it("does not render unvalidated audit rows", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          auditResponse([{ ...auditEvent, credentials: "private" }]),
        ),
    );
    render(<AuditPanel onSessionExpired={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load the audit trail",
    );
    expect(screen.queryByText("Nora Admin")).toBeNull();
    expect(screen.queryByText("private")).toBeNull();
  });

  it("retains confirmed rows while refreshing and after a failure, then replaces them on success", async () => {
    const refresh = deferred<Response>();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(auditResponse())
      .mockReturnValueOnce(refresh.promise)
      .mockResolvedValueOnce(auditResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    render(<AuditPanel onSessionExpired={vi.fn()} />);
    await screen.findByText("Alert acknowledged");
    fireEvent.click(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    );

    expect(screen.getByRole("status")).toHaveTextContent(
      "Refreshing audit trail",
    );
    expect(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    ).toBeDisabled();
    expect(screen.getByText("Alert acknowledged")).toBeVisible();
    await act(async () => refresh.resolve(jsonResponse({}, 503)));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "last confirmed audit trail is still shown",
    );
    expect(screen.getByText("Alert acknowledged")).toBeVisible();

    fireEvent.click(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    );
    expect(
      await screen.findByText(
        "No audit events have been recorded for your organisation.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("Alert acknowledged")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it.each([false, true])(
    "shows server-authoritative denial and removes any existing rows (refresh: %s)",
    async (refresh) => {
      const fetchMock = vi.fn<typeof fetch>();
      if (refresh) fetchMock.mockResolvedValueOnce(auditResponse());
      fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
      vi.stubGlobal("fetch", fetchMock);
      const onSessionExpired = vi.fn();
      render(<AuditPanel onSessionExpired={onSessionExpired} />);
      if (refresh) {
        await screen.findByText("Alert acknowledged");
        fireEvent.click(
          screen.getByRole("button", { name: "Refresh audit trail" }),
        );
      }

      expect(
        await screen.findByRole("heading", { name: "Audit access denied" }),
      ).toBeVisible();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "server did not grant access",
      );
      expect(screen.queryByRole("table")).toBeNull();
      expect(screen.queryByText("Nora Admin")).toBeNull();
      expect(onSessionExpired).not.toHaveBeenCalled();
    },
  );

  it.each([false, true])(
    "clears the audit trail and delegates session expiry to App (refresh: %s)",
    async (refresh) => {
      const fetchMock = vi.fn<typeof fetch>();
      if (refresh) fetchMock.mockResolvedValueOnce(auditResponse());
      fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
      vi.stubGlobal("fetch", fetchMock);
      const onSessionExpired = vi.fn();
      render(<AuditPanel onSessionExpired={onSessionExpired} />);
      if (refresh) {
        await screen.findByText("Alert acknowledged");
        fireEvent.click(
          screen.getByRole("button", { name: "Refresh audit trail" }),
        );
      }

      await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());
      expect(screen.getByRole("status")).toHaveTextContent(
        "Your session expired",
      );
      expect(screen.queryByRole("table")).toBeNull();
      expect(screen.queryByText("Nora Admin")).toBeNull();
      expect(
        screen.getByRole("button", { name: "Refresh audit trail" }),
      ).toBeDisabled();
    },
  );

  it("aborts an unfinished read on unmount and ignores its late expiry response", async () => {
    const request = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>().mockReturnValue(request.promise);
    vi.stubGlobal("fetch", fetchMock);
    const onSessionExpired = vi.fn();
    const { unmount } = render(
      <AuditPanel onSessionExpired={onSessionExpired} />,
    );
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;

    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => request.resolve(jsonResponse({}, 401)));
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("ignores a superseded response after its effect is replaced", async () => {
    const stale = deferred<Response>();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce(auditResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(<AuditPanel onSessionExpired={vi.fn()} />);
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    rerender(<AuditPanel onSessionExpired={vi.fn()} />);
    expect(
      await screen.findByText(
        "No audit events have been recorded for your organisation.",
      ),
    ).toBeVisible();
    await act(async () => stale.resolve(auditResponse()));

    expect(signal?.aborted).toBe(true);
    expect(screen.queryByText("Nora Admin")).toBeNull();
    expect(
      screen.getByText(
        "No audit events have been recorded for your organisation.",
      ),
    ).toBeVisible();
  });

  it("times out an audit read and offers a manual retry", async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn<typeof fetch>(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("Request aborted", "AbortError")),
            { once: true },
          );
        }),
    );
    vi.stubGlobal("fetch", fetchMock);
    render(<AuditPanel onSessionExpired={vi.fn()} />);
    await act(async () => vi.advanceTimersByTimeAsync(5_000));

    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "could not load the audit trail",
    );
    expect(
      screen.getByRole("button", { name: "Retry audit trail" }),
    ).toBeEnabled();
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});
