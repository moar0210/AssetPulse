import { afterEach, describe, expect, it, vi } from "vitest";

import {
  MAX_TELEMETRY_LIMIT,
  TelemetrySensorNotFoundError,
  TelemetrySessionExpiredError,
  getTelemetryReadings,
} from "./telemetry";

const SENSOR_ID = "30000000-0000-0000-0000-000000000001";
const FROM = "2026-08-15T11:00:00.000Z";
const TO = "2026-08-15T12:00:00.000Z";

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function validPayload() {
  return {
    sensorId: SENSOR_ID,
    from: FROM,
    to: TO,
    readings: [
      {
        id: "60000000-0000-0000-0000-000000000001",
        value: 71.5,
        observedAt: "2026-08-15T11:15:00Z",
      },
      {
        id: "60000000-0000-0000-0000-000000000002",
        value: 83.25,
        observedAt: "2026-08-15T11:45:00Z",
      },
    ],
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("telemetry API", () => {
  it("requests an encoded bounded range with same-origin credentials", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () =>
      jsonResponse(validPayload()),
    );
    vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController();

    await expect(
      getTelemetryReadings(SENSOR_ID, FROM, TO, 200, controller.signal),
    ).resolves.toEqual(validPayload());

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/sensors/${SENSOR_ID}/telemetry-readings?from=2026-08-15T11%3A00%3A00.000Z&to=2026-08-15T12%3A00%3A00.000Z&limit=200`,
      {
        method: "GET",
        headers: { Accept: "application/json" },
        credentials: "same-origin",
        signal: controller.signal,
      },
    );
  });

  it("maps expired and absent sensors without parsing problem details", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 401))
      .mockResolvedValueOnce(jsonResponse({}, 404));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      getTelemetryReadings(SENSOR_ID, FROM, TO),
    ).rejects.toBeInstanceOf(TelemetrySessionExpiredError);
    await expect(
      getTelemetryReadings(SENSOR_ID, FROM, TO),
    ).rejects.toBeInstanceOf(TelemetrySensorNotFoundError);
  });

  it("rejects invalid local bounds before issuing a request", async () => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTelemetryReadings("not-a-uuid", FROM, TO)).rejects.toThrow(
      "sensor identifier",
    );
    await expect(getTelemetryReadings(SENSOR_ID, TO, FROM)).rejects.toThrow(
      "telemetry range",
    );
    await expect(getTelemetryReadings(SENSOR_ID, FROM, FROM)).rejects.toThrow(
      "telemetry range",
    );
    await expect(
      getTelemetryReadings(SENSOR_ID, "2026-08-14T11:59:59.999Z", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, "2026-08-14T11:59:59.999999999Z", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, "2026-02-30T11:00:00Z", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, "2026-08-15T11:00:00", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, "08/15/2026 11:00 UTC", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, "2026-08-15T11:00:00+18:01", TO),
    ).rejects.toThrow("telemetry range");
    await expect(
      getTelemetryReadings(SENSOR_ID, FROM, TO, MAX_TELEMETRY_LIMIT + 1),
    ).rejects.toThrow("telemetry limit");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts timezone-qualified bounds and a canonical equivalent response", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () =>
      jsonResponse(validPayload()),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      getTelemetryReadings(
        SENSOR_ID,
        "2026-08-15T13:00:00+02:00",
        "2026-08-15T14:00:00+02:00",
      ),
    ).resolves.toEqual(validPayload());

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/v1/sensors/${SENSOR_ID}/telemetry-readings?from=2026-08-15T13%3A00%3A00%2B02%3A00&to=2026-08-15T14%3A00%3A00%2B02%3A00&limit=100`,
      expect.objectContaining({ method: "GET" }),
    );
  });

  it.each([
    ["extra top-level field", { ...validPayload(), organisationId: "hidden" }],
    [
      "wrong sensor",
      {
        ...validPayload(),
        sensorId: "30000000-0000-0000-0000-000000000002",
      },
    ],
    [
      "reading before the half-open range",
      {
        ...validPayload(),
        readings: [
          {
            id: "60000000-0000-0000-0000-000000000001",
            value: 71,
            observedAt: "2026-08-15T10:59:59.999999999Z",
          },
        ],
      },
    ],
    [
      "reading at the exclusive upper bound",
      {
        ...validPayload(),
        readings: [
          {
            id: "60000000-0000-0000-0000-000000000001",
            value: 71,
            observedAt: TO,
          },
        ],
      },
    ],
    [
      "descending readings",
      {
        ...validPayload(),
        readings: [...validPayload().readings].reverse(),
      },
    ],
    [
      "descending IDs at the same instant",
      {
        ...validPayload(),
        readings: [
          {
            ...validPayload().readings[0],
            id: "60000000-0000-0000-0000-000000000002",
          },
          {
            ...validPayload().readings[0],
            id: "60000000-0000-0000-0000-000000000001",
          },
        ],
      },
    ],
    [
      "duplicate reading ID",
      {
        ...validPayload(),
        readings: [
          validPayload().readings[0],
          {
            ...validPayload().readings[1],
            id: validPayload().readings[0].id,
          },
        ],
      },
    ],
    [
      "extra reading field",
      {
        ...validPayload(),
        readings: [{ ...validPayload().readings[0], organisationId: "hidden" }],
      },
    ],
    [
      "invalid numeric value",
      {
        ...validPayload(),
        readings: [{ ...validPayload().readings[0], value: Number.NaN }],
      },
    ],
  ])("rejects %s", async (_description, payload) => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>(async () => jsonResponse(payload)),
    );

    await expect(getTelemetryReadings(SENSOR_ID, FROM, TO)).rejects.toThrow(
      "unexpected payload",
    );
  });

  it("accepts nanosecond ordering and the inclusive lower bound", async () => {
    const payload = {
      ...validPayload(),
      readings: [
        {
          id: "60000000-0000-0000-0000-000000000003",
          value: 70,
          observedAt: FROM,
        },
        {
          id: "60000000-0000-0000-0000-000000000002",
          value: 71,
          observedAt: "2026-08-15T11:00:00.000001Z",
        },
        {
          id: "60000000-0000-0000-0000-000000000001",
          value: 72,
          observedAt: "2026-08-15T11:00:00.000002Z",
        },
      ],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>(async () => jsonResponse(payload)),
    );

    await expect(getTelemetryReadings(SENSOR_ID, FROM, TO)).resolves.toEqual(
      payload,
    );
  });

  it("enforces the requested result limit on the response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>(async () => jsonResponse(validPayload())),
    );

    await expect(getTelemetryReadings(SENSOR_ID, FROM, TO, 1)).rejects.toThrow(
      "unexpected payload",
    );
  });

  it("rejects non-JSON and unsuccessful responses", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response("text", {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      )
      .mockResolvedValueOnce(jsonResponse({}, 503));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTelemetryReadings(SENSOR_ID, FROM, TO)).rejects.toThrow(
      "content type",
    );
    await expect(getTelemetryReadings(SENSOR_ID, FROM, TO)).rejects.toThrow(
      "unsuccessful",
    );
  });
});
