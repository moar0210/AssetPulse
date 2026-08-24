import { describe, expect, it, vi } from "vitest";

import {
  acknowledgeAlert,
  AlertCommandUncertainError,
  AlertForbiddenError,
  AlertNotFoundError,
  AlertRequestVerificationError,
  AlertSessionExpiredError,
  AlertStateConflictError,
  getAlertDetail,
  getAlerts,
  resolveAlert,
  subscribeToAlertChanges,
} from "./alerts";
import type { AlertEventSourceFactory } from "./alerts";

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
  occurrenceCount: 3,
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
      instance: "/api/v1/alerts",
      code,
      correlationId: "correlation-id",
    }),
    {
      status,
      headers: { "Content-Type": "application/problem+json" },
    },
  );
}

describe("alert API client", () => {
  it("loads a bounded, strictly typed alert queue", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ alerts: [summary], limit: 50 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAlerts()).resolves.toEqual({
      alerts: [summary],
      limit: 50,
    });
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/alerts?limit=50", {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
      signal: undefined,
    });
  });

  it("rejects extra fields, duplicate ids, and invalid queue order", async () => {
    const olderId = "50000000-0000-0000-0000-000000000002";
    const malformedPayloads = [
      { alerts: [summary], limit: 50, organisationId: "secret" },
      { alerts: [summary, summary], limit: 50 },
      {
        alerts: [
          { ...summary, id: olderId, lastOccurredAt: "2026-08-23T10:02:00Z" },
          summary,
        ],
        limit: 50,
      },
    ];

    for (const payload of malformedPayloads) {
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
      );
      await expect(getAlerts()).rejects.toThrow("unexpected payload");
    }
  });

  it("loads exact detail and enforces status-history coherence", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(acknowledgedDetail)),
    );

    await expect(getAlertDetail(alertId)).resolves.toEqual(acknowledgedDetail);

    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse({ ...acknowledgedDetail, history: [] }),
        ),
    );
    await expect(getAlertDetail(alertId)).rejects.toThrow("unexpected payload");
  });

  it("rejects detail whose cooldown ends before its latest occurrence", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          ...openDetail,
          cooldownUntil: "2026-08-23T10:02:59Z",
        }),
      ),
    );

    await expect(getAlertDetail(alertId)).rejects.toThrow("unexpected payload");
  });

  it("classifies session, permission, and missing-alert read failures", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(getAlerts()).rejects.toBeInstanceOf(AlertSessionExpiredError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
    await expect(getAlerts()).rejects.toBeInstanceOf(AlertForbiddenError);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 404));
    await expect(getAlertDetail(alertId)).rejects.toBeInstanceOf(
      AlertNotFoundError,
    );
  });

  it("submits bodyless commands with the in-memory CSRF header", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(acknowledgedDetail));
    vi.stubGlobal("fetch", fetchMock);

    await expect(acknowledgeAlert(alertId, csrfToken)).resolves.toEqual(
      acknowledgedDetail,
    );
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/alerts/${alertId}/acknowledge`,
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
  });

  it("classifies every protected command denial", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    fetchMock.mockResolvedValueOnce(jsonResponse({}, 401));
    await expect(resolveAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertSessionExpiredError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 403));
    await expect(resolveAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertForbiddenError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 404));
    await expect(resolveAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertNotFoundError,
    );
    fetchMock.mockResolvedValueOnce(jsonResponse({}, 409));
    await expect(resolveAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertStateConflictError,
    );
  });

  it("distinguishes rejected CSRF verification from role denial", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(problemResponse("CSRF_REJECTED", 403)),
    );

    await expect(acknowledgeAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertRequestVerificationError,
    );
  });

  it("marks a lost or malformed successful command response as uncertain", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockRejectedValue(new TypeError("connection lost")),
    );
    await expect(acknowledgeAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertCommandUncertainError,
    );

    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ id: alertId })),
    );
    await expect(acknowledgeAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertCommandUncertainError,
    );
  });

  it("rejects a valid acknowledge response that did not reach ACKNOWLEDGED", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(openDetail)),
    );

    await expect(acknowledgeAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertCommandUncertainError,
    );
  });

  it("rejects a valid resolve response that did not reach RESOLVED", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(acknowledgedDetail)),
    );

    await expect(resolveAlert(alertId, csrfToken)).rejects.toBeInstanceOf(
      AlertCommandUncertainError,
    );
  });

  it("rejects invalid identifiers, limits, and successful non-JSON data", async () => {
    await expect(getAlerts(101)).rejects.toThrow("limit");
    await expect(getAlertDetail("foreign-alert")).rejects.toThrow(
      "valid alert identifier",
    );

    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ alerts: [], limit: 50 }), {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      ),
    );
    await expect(getAlerts()).rejects.toThrow("unexpected content type");
  });
});

describe("alert event stream client", () => {
  it("subscribes to exact typed changes and closes cleanly", () => {
    const listeners = new Map<string, EventListener>();
    const close = vi.fn();
    const createEventSource = vi.fn<AlertEventSourceFactory>((url, init) => {
      expect(url).toBe("/api/v1/alerts/stream");
      expect(init).toEqual({ withCredentials: true });
      return {
        addEventListener: (
          type: string,
          listener: EventListenerOrEventListenerObject,
        ) => {
          listeners.set(type, listener as EventListener);
        },
        close,
      } as unknown as EventSource;
    });
    const onOpen = vi.fn();
    const onChange = vi.fn();
    const onError = vi.fn();
    const onProtocolError = vi.fn();
    const unsubscribe = subscribeToAlertChanges(
      { onOpen, onChange, onError, onProtocolError },
      createEventSource,
    );

    listeners.get("open")?.(new Event("open"));
    listeners.get("alert-changed")?.(
      new MessageEvent("alert-changed", {
        data: JSON.stringify({
          alertId,
          changeType: "OCCURRENCE_RECORDED",
        }),
      }),
    );

    expect(onOpen).toHaveBeenCalledOnce();
    expect(onChange).toHaveBeenCalledWith({
      alertId,
      changeType: "OCCURRENCE_RECORDED",
    });
    expect(onError).not.toHaveBeenCalled();
    expect(onProtocolError).not.toHaveBeenCalled();

    listeners.get("alert-changed")?.(
      new MessageEvent("alert-changed", {
        data: JSON.stringify({ alertId, changeType: "UNKNOWN" }),
      }),
    );
    expect(onProtocolError).toHaveBeenCalledOnce();
    expect(onError).not.toHaveBeenCalled();

    listeners.get("error")?.(new Event("error"));
    expect(onError).toHaveBeenCalledOnce();

    unsubscribe();
    expect(close).toHaveBeenCalledOnce();
  });
});
