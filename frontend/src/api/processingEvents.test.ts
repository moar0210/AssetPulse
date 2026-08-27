import { describe, expect, it, vi } from "vitest";

import {
  getDeadProcessingEvents,
  ProcessingEventForbiddenError,
  ProcessingEventNotFoundError,
  ProcessingEventRequestVerificationError,
  ProcessingEventRetryUncertainError,
  ProcessingEventSessionExpiredError,
  ProcessingEventStateConflictError,
  retryDeadProcessingEvent,
} from "./processingEvents";

const eventId = "60000000-0000-0000-0000-000000000001";
const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
} as const;

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
      instance: "/api/v1/processing-events",
      code,
      correlationId: "correlation-id",
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

describe("processing-events API client", () => {
  it("loads the bounded exact dead-event queue", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ events: [deadEvent], limit: 50 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getDeadProcessingEvents()).resolves.toEqual({
      events: [deadEvent],
      limit: 50,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/processing-events/dead?limit=50",
      {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        signal: undefined,
      },
    );
  });

  it("rejects non-exact, unsafe, incoherent, duplicate, and unordered data", async () => {
    const olderEvent = {
      ...deadEvent,
      id: "60000000-0000-0000-0000-000000000002",
      telemetryBatchId: "70000000-0000-0000-0000-000000000002",
      deadAt: "2026-08-23T10:04:00Z",
      updatedAt: "2026-08-23T10:04:01Z",
    };
    const malformedPayloads = [
      { events: [deadEvent], limit: 50, organisationId: "secret" },
      { events: [{ ...deadEvent, claimOwner: "worker-secret" }], limit: 50 },
      { events: [{ ...deadEvent, attemptCount: 4 }], limit: 50 },
      {
        events: [{ ...deadEvent, eventType: "INTERNAL_EVENT" }],
        limit: 50,
      },
      {
        events: [{ ...deadEvent, lastErrorMessage: "java.lang.Exception" }],
        limit: 50,
      },
      {
        events: [{ ...deadEvent, deadAt: "2026-08-23T09:59:59Z" }],
        limit: 50,
      },
      { events: [deadEvent, deadEvent], limit: 50 },
      { events: [olderEvent, deadEvent], limit: 50 },
    ];

    for (const payload of malformedPayloads) {
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
      );
      await expect(getDeadProcessingEvents()).rejects.toThrow(
        "unexpected payload",
      );
    }
  });

  it("accepts only paired fixed failure details", async () => {
    const leaseExpiredEvent = {
      ...deadEvent,
      lastErrorCode: "LEASE_EXPIRED",
      lastErrorMessage: "Processing lease expired after the final attempt.",
    } as const;
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse({ events: [leaseExpiredEvent], limit: 50 }),
        ),
    );

    await expect(getDeadProcessingEvents()).resolves.toEqual({
      events: [leaseExpiredEvent],
      limit: 50,
    });
  });

  it("classifies expired-session and forbidden reads", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(getDeadProcessingEvents()).rejects.toBeInstanceOf(
      ProcessingEventSessionExpiredError,
    );

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
    await expect(getDeadProcessingEvents()).rejects.toBeInstanceOf(
      ProcessingEventForbiddenError,
    );
  });

  it("submits a bodyless retry with in-memory CSRF and accepts only 204", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(null, {
        status: 204,
        headers: { "Cache-Control": "no-store" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/processing-events/${eventId}/retry`,
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "X-CSRF-TOKEN": "csrf-token-value",
        },
        credentials: "same-origin",
        signal: undefined,
      },
    );
    expect(fetchMock.mock.calls[0]?.[1]?.body).toBeUndefined();

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 200));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventRetryUncertainError);
  });

  it("classifies every protected retry denial", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventSessionExpiredError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventForbiddenError);

    fetchMock.mockResolvedValueOnce(problemResponse("CSRF_REJECTED", 403));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventRequestVerificationError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 404));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventNotFoundError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 409));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventStateConflictError);
  });

  it("classifies transport and server ambiguity as uncertain", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockRejectedValueOnce(new TypeError("connection lost"));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventRetryUncertainError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 503));
    await expect(
      retryDeadProcessingEvent(eventId, csrfToken),
    ).rejects.toBeInstanceOf(ProcessingEventRetryUncertainError);
  });

  it("rejects invalid identifiers, limits, and successful non-JSON data", async () => {
    await expect(getDeadProcessingEvents(101)).rejects.toThrow("limit");
    await expect(
      retryDeadProcessingEvent("foreign-event", csrfToken),
    ).rejects.toThrow("valid processing-event identifier");

    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ events: [], limit: 50 }), {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      ),
    );
    await expect(getDeadProcessingEvents()).rejects.toThrow(
      "unexpected content type",
    );
  });
});
