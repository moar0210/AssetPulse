import { describe, expect, it, vi } from "vitest";

import {
  AuditForbiddenError,
  AuditSessionExpiredError,
  getAuditEvents,
} from "./auditEvents";

const eventId = "a0000000-0000-0000-0000-000000000002";
const actorId = "10000000-0000-0000-0000-000000000001";
const subjectId = "50000000-0000-0000-0000-000000000001";
const auditEvent = {
  id: eventId,
  actor: { id: actorId, displayName: "Nora Admin" },
  action: "ALERT_ACKNOWLEDGED",
  subject: { type: "ALERT", id: subjectId },
  occurredAt: "2026-08-28T10:00:00.123456789Z",
  correlationId: "b0000000-0000-0000-0000-000000000001",
} as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function mockEvents(events: readonly unknown[], limit = 50) {
  const fetchMock = vi
    .fn<typeof fetch>()
    .mockResolvedValue(jsonResponse({ events, limit }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("AUD-01 audit-events API client", () => {
  it("reads the bounded exact audit trail with a same-origin session and no cache", async () => {
    const controller = new AbortController();
    const fetchMock = mockEvents([auditEvent]);

    await expect(getAuditEvents(undefined, controller.signal)).resolves.toEqual(
      {
        events: [auditEvent],
        limit: 50,
      },
    );
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith(
      "/api/v1/audit-events?limit=50",
      {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        cache: "no-store",
        signal: controller.signal,
      },
    );
  });

  it.each([
    ["AUTHENTICATION_SUCCEEDED", "USER"],
    ["SESSION_ENDED", "USER"],
    ["ALERT_ACKNOWLEDGED", "ALERT"],
    ["ALERT_RESOLVED", "ALERT"],
    ["WORK_ORDER_CREATED", "WORK_ORDER"],
    ["WORK_ORDER_ASSIGNED", "WORK_ORDER"],
    ["WORK_ORDER_STARTED", "WORK_ORDER"],
    ["WORK_ORDER_COMPLETED", "WORK_ORDER"],
    ["PROCESSING_EVENT_RETRIED", "PROCESSING_EVENT"],
  ] as const)(
    "accepts %s only with its supported %s subject",
    async (action, type) => {
      const event = {
        ...auditEvent,
        action,
        subject: { type, id: type === "USER" ? actorId : subjectId },
      };
      mockEvents([event]);
      await expect(getAuditEvents()).resolves.toEqual({
        events: [event],
        limit: 50,
      });

      mockEvents([
        {
          ...event,
          subject: { type: type === "USER" ? "ALERT" : "USER", id: actorId },
        },
      ]);
      await expect(getAuditEvents()).rejects.toThrow("unexpected payload");
    },
  );

  it("rejects non-exact wrappers and event, actor, or subject records", async () => {
    const invalidPayloads = [
      null,
      [],
      { events: [auditEvent] },
      { events: [auditEvent], limit: 50, organisationId: actorId },
      { events: {}, limit: 50 },
      { events: [{ ...auditEvent, rawTelemetry: "private" }], limit: 50 },
      { events: [{ ...auditEvent, correlationId: undefined }], limit: 50 },
      { events: [{ ...auditEvent, actor: null }], limit: 50 },
      { events: [{ ...auditEvent, actor: { id: actorId } }], limit: 50 },
      {
        events: [
          { ...auditEvent, actor: { ...auditEvent.actor, email: "private" } },
        ],
        limit: 50,
      },
      { events: [{ ...auditEvent, subject: null }], limit: 50 },
      { events: [{ ...auditEvent, subject: { id: subjectId } }], limit: 50 },
      {
        events: [
          {
            ...auditEvent,
            subject: { ...auditEvent.subject, payload: "private" },
          },
        ],
        limit: 50,
      },
    ];

    for (const payload of invalidPayloads) {
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
      );
      await expect(getAuditEvents()).rejects.toThrow("unexpected payload");
    }
  });

  it("rejects malformed identities, unsafe display names, and unknown or anonymous actions", async () => {
    const invalidEvents = [
      { ...auditEvent, id: "not-a-uuid" },
      { ...auditEvent, correlationId: "not-a-uuid" },
      { ...auditEvent, actor: { ...auditEvent.actor, id: "not-a-uuid" } },
      { ...auditEvent, subject: { ...auditEvent.subject, id: "not-a-uuid" } },
      { ...auditEvent, actor: { ...auditEvent.actor, displayName: "" } },
      {
        ...auditEvent,
        actor: { ...auditEvent.actor, displayName: " Nora Admin" },
      },
      {
        ...auditEvent,
        actor: { ...auditEvent.actor, displayName: "x".repeat(121) },
      },
      { ...auditEvent, action: "INTERNAL_EVENT" },
      { ...auditEvent, action: "toString" },
      { ...auditEvent, action: "AUTHENTICATION_FAILED" },
      {
        ...auditEvent,
        action: "AUTHENTICATION_FAILED",
        actor: null,
        subject: null,
      },
    ];

    for (const event of invalidEvents) {
      mockEvents([event]);
      await expect(getAuditEvents()).rejects.toThrow("unexpected payload");
    }
  });

  it.each(["AUTHENTICATION_SUCCEEDED", "SESSION_ENDED"] as const)(
    "rejects %s attributed to a different user",
    async (action) => {
      mockEvents([
        { ...auditEvent, action, subject: { type: "USER", id: subjectId } },
      ]);
      await expect(getAuditEvents()).rejects.toThrow("unexpected payload");

      const userId = "a0000000-0000-0000-0000-000000000001";
      const event = {
        ...auditEvent,
        actor: { ...auditEvent.actor, id: userId.toUpperCase() },
        action,
        subject: { type: "USER", id: userId },
      };
      mockEvents([event]);
      await expect(getAuditEvents()).resolves.toEqual({
        events: [event],
        limit: 50,
      });
    },
  );

  it.each([
    "2026-02-29T10:00:00Z",
    "2026-08-32T10:00:00Z",
    "2026-08-28T24:00:00Z",
    "2026-08-28T10:60:00Z",
    "2026-08-28T10:00:60Z",
    "2026-08-28T10:00:00",
    "2026-08-28T10:00:00+19:00",
    "2026-08-28T10:00:00+18:01",
    "2026-08-28T10:00:00+01:60",
    "2026-08-28T10:00:00.1234567890Z",
    " 2026-08-28T10:00:00Z",
    "yesterday",
  ])("rejects an invalid audit timestamp: %s", async (occurredAt) => {
    mockEvents([{ ...auditEvent, occurredAt }]);
    await expect(getAuditEvents()).rejects.toThrow("unexpected payload");
  });

  it("requires descending instants and IDs, including nanoseconds and offset-equivalent instants", async () => {
    const olderEvent = {
      ...auditEvent,
      id: "a0000000-0000-0000-0000-000000000003",
      occurredAt: "2026-08-28T12:00:00.123456788+02:00",
    };
    const tiedEvent = {
      ...auditEvent,
      id: "a0000000-0000-0000-0000-000000000001",
      occurredAt: "2026-08-28T12:00:00.123456789+02:00",
    };
    mockEvents([auditEvent, tiedEvent, olderEvent]);
    await expect(getAuditEvents()).resolves.toEqual({
      events: [auditEvent, tiedEvent, olderEvent],
      limit: 50,
    });

    for (const events of [
      [olderEvent, auditEvent],
      [tiedEvent, auditEvent],
    ]) {
      mockEvents(events);
      await expect(getAuditEvents()).rejects.toThrow("unexpected payload");
    }
  });

  it("rejects duplicate IDs regardless of case, excess rows, and incorrect echoed limits", async () => {
    mockEvents([auditEvent, { ...auditEvent, id: eventId.toUpperCase() }]);
    await expect(getAuditEvents()).rejects.toThrow("unexpected payload");

    mockEvents(
      [
        auditEvent,
        { ...auditEvent, id: "a0000000-0000-0000-0000-000000000001" },
      ],
      1,
    );
    await expect(getAuditEvents(1)).rejects.toThrow("unexpected payload");

    mockEvents([], 50);
    await expect(getAuditEvents(1)).rejects.toThrow("unexpected payload");
  });

  it.each([1, 100])("accepts a supported custom bound of %s", async (limit) => {
    const fetchMock = mockEvents([], limit);
    await expect(getAuditEvents(limit)).resolves.toEqual({ events: [], limit });
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      `/api/v1/audit-events?limit=${limit}`,
    );
  });

  it("rejects invalid limits before making a request", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);
    for (const limit of [
      0,
      -1,
      101,
      1.5,
      NaN,
      Infinity,
      Number.MAX_SAFE_INTEGER + 1,
    ]) {
      await expect(getAuditEvents(limit)).rejects.toThrow(
        "limit from 1 through 100",
      );
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("classifies expired sessions and forbidden access before parsing the body", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 }));
    await expect(getAuditEvents()).rejects.toBeInstanceOf(
      AuditSessionExpiredError,
    );
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 403 }));
    await expect(getAuditEvents()).rejects.toBeInstanceOf(AuditForbiddenError);
  });

  it("rejects unsuccessful, non-JSON, invalid JSON, and transport responses", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 503));
    await expect(getAuditEvents()).rejects.toThrow("unsuccessful response");
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ events: [], limit: 50 }), {
        headers: { "Content-Type": "text/plain" },
      }),
    );
    await expect(getAuditEvents()).rejects.toThrow("unexpected content type");
    fetchMock.mockResolvedValueOnce(
      new Response("{", { headers: { "Content-Type": "application/json" } }),
    );
    await expect(getAuditEvents()).rejects.toThrow();
    fetchMock.mockRejectedValueOnce(new TypeError("connection lost"));
    await expect(getAuditEvents()).rejects.toThrow("connection lost");
  });
});
