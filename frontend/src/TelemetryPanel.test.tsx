import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { TelemetryPanel } from "./TelemetryPanel";
import type { Sensor } from "./api/assets";

const NOW = new Date("2026-08-15T12:00:00.000Z");
const SENSOR: Sensor = {
  id: "30000000-0000-0000-0000-000000000001",
  sensorKey: "PUMP-101-TEMP",
  name: "Pump casing temperature",
  measurementType: "TEMPERATURE",
  unit: "CELSIUS",
  thresholdRules: [],
};
const SECOND_SENSOR: Sensor = {
  ...SENSOR,
  id: "30000000-0000-0000-0000-000000000002",
  sensorKey: "PUMP-101-OUTLET-TEMP",
  name: "Pump outlet temperature",
};

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function formattedCelsius(value: number) {
  return `${new Intl.NumberFormat(undefined, {
    maximumFractionDigits: 6,
  }).format(value)} °C`;
}

function range(readings: readonly unknown[], sensorId = SENSOR.id) {
  return {
    sensorId,
    from: "2026-08-14T12:00:00.000Z",
    to: "2026-08-15T12:00:00.000Z",
    readings,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function reading(
  value: number,
  index = 1,
  observedAt = "2026-08-15T11:15:00Z",
) {
  return {
    id: `60000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
    value,
    observedAt,
  };
}

afterEach(() => vi.unstubAllGlobals());

describe("TelemetryPanel", () => {
  it("renders a chart and semantic chronological table and refreshes", async () => {
    const payload = range([
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
    ]);
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(payload))
      .mockResolvedValueOnce(
        jsonResponse(range([reading(86.125, 3, "2026-08-15T11:55:00Z")])),
      );
    vi.stubGlobal("fetch", fetchMock);

    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    const chart = await screen.findByRole("img", { name: /Recent readings/ });
    expect(chart).toBeVisible();
    const plottedXCoordinates = chart
      .querySelector(".telemetry-chart__line")
      ?.getAttribute("points")
      ?.split(" ")
      .map((point) => Number(point.split(",", 1)[0]));
    expect(plottedXCoordinates).toEqual([34, 606]);
    const table = screen.getByRole("table", {
      name: "Recent readings for Pump casing temperature",
    });
    const tableRegion = screen.getByRole("region", {
      name: "Recent readings for Pump casing temperature",
    });
    expect(table).toHaveTextContent(formattedCelsius(71.5));
    expect(table).toHaveTextContent(formattedCelsius(83.25));
    expect(table.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(table.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-08-15T11:15:00Z",
    );
    expect(tableRegion).toHaveAttribute("tabindex", "0");

    fireEvent.click(screen.getByRole("button", { name: "Refresh telemetry" }));
    const refreshedTable = await screen.findByRole("table");
    expect(refreshedTable).toHaveTextContent(formattedCelsius(86.125));
    expect(refreshedTable).not.toHaveTextContent(formattedCelsius(71.5));
    expect(refreshedTable).not.toHaveTextContent(formattedCelsius(83.25));
    expect(refreshedTable.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("renders an explicit empty range", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>(async () => jsonResponse(range([]))),
    );

    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    expect(
      await screen.findByText(
        "No telemetry readings are available in the last 24 hours.",
      ),
    ).toBeVisible();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("retries an unavailable range", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        jsonResponse(
          {
            detail: "Database telemetry password is secret-value",
            trace: "internal stack details",
          },
          503,
        ),
      )
      .mockResolvedValueOnce(jsonResponse(range([])));
    vi.stubGlobal("fetch", fetchMock);

    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load recent telemetry",
    );
    expect(screen.queryByText(/secret-value|internal stack/i)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Retry telemetry" }));
    expect(
      await screen.findByText(
        "No telemetry readings are available in the last 24 hours.",
      ),
    ).toBeVisible();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("shows a generic not-found state without exposing problem details", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>(async () =>
        jsonResponse(
          {
            code: "SENSOR_NOT_FOUND",
            detail: "Sensor belongs to Riverside",
          },
          404,
        ),
      ),
    );

    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    expect(
      await screen.findByText("Telemetry for this sensor is not available."),
    ).toBeVisible();
    expect(screen.queryByText(/Riverside|SENSOR_NOT_FOUND/)).toBeNull();
  });

  it("does not reload when equivalent sensor props are recreated", async () => {
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(range([])));
    const handleSessionExpired = vi.fn();
    const now = () => NOW;
    vi.stubGlobal("fetch", fetchMock);

    const { rerender } = render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={handleSessionExpired}
        now={now}
      />,
    );

    expect(
      await screen.findByText(
        "No telemetry readings are available in the last 24 hours.",
      ),
    ).toBeVisible();

    rerender(
      <TelemetryPanel
        sensors={[{ ...SENSOR }]}
        onSessionExpired={handleSessionExpired}
        now={now}
      />,
    );

    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("loads the selected sensor without retaining another sensor's readings", async () => {
    const firstReading = {
      id: "60000000-0000-0000-0000-000000000001",
      value: 71.5,
      observedAt: "2026-08-15T11:15:00Z",
    };
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      const sensorId = new URL(
        String(input),
        "http://localhost",
      ).pathname.split("/")[4];
      return sensorId === SECOND_SENSOR.id
        ? jsonResponse(range([], SECOND_SENSOR.id))
        : jsonResponse(range([firstReading]));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <TelemetryPanel
        sensors={[SENSOR, SECOND_SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    expect(
      await screen.findByRole("table", {
        name: "Recent readings for Pump casing temperature",
      }),
    ).toHaveTextContent(formattedCelsius(71.5));
    expect(
      screen
        .getByRole("img", { name: /Recent readings/ })
        .querySelector(".telemetry-chart__point"),
    ).toHaveAttribute("cx", "320");
    fireEvent.change(screen.getByLabelText("Sensor"), {
      target: { value: SECOND_SENSOR.id },
    });

    expect(
      await screen.findByText(
        "No telemetry readings are available in the last 24 hours.",
      ),
    ).toBeVisible();
    expect(screen.queryByText("71.5 °C")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it.each(["success", "failure", "session-expired"])(
    "ignores a delayed previous sensor %s after the selected sensor loads",
    async (outcome) => {
      const previousResponse = deferred<Response>();
      const fetchMock = vi
        .fn<typeof fetch>()
        .mockReturnValueOnce(previousResponse.promise)
        .mockResolvedValueOnce(
          jsonResponse(range([reading(83.25)], SECOND_SENSOR.id)),
        );
      const onSessionExpired = vi.fn();
      vi.stubGlobal("fetch", fetchMock);

      render(
        <TelemetryPanel
          sensors={[SENSOR, SECOND_SENSOR]}
          onSessionExpired={onSessionExpired}
          now={() => NOW}
        />,
      );
      const previousSignal = fetchMock.mock.calls[0]?.[1]?.signal;
      fireEvent.change(screen.getByLabelText("Sensor"), {
        target: { value: SECOND_SENSOR.id },
      });
      const table = await screen.findByRole("table", {
        name: "Recent readings for Pump outlet temperature",
      });
      expect(previousSignal?.aborted).toBe(true);

      await act(async () => {
        previousResponse.resolve(
          outcome === "success"
            ? jsonResponse(range([reading(71.5)]))
            : jsonResponse({}, outcome === "session-expired" ? 401 : 503),
        );
      });

      expect(table).toBeVisible();
      expect(table).toHaveTextContent(formattedCelsius(83.25));
      expect(screen.queryByText(formattedCelsius(71.5))).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(onSessionExpired).not.toHaveBeenCalled();
      expect(fetchMock).toHaveBeenCalledTimes(2);
    },
  );

  it("aborts an unmounted request, clears its timeout and ignores late session expiry", async () => {
    vi.useFakeTimers();
    const pendingResponse = deferred<Response>();
    const fetchMock = vi.fn<typeof fetch>(() => pendingResponse.promise);
    const onSessionExpired = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const { unmount } = render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={onSessionExpired}
        now={() => NOW}
      />,
    );
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;
    expect(signal?.aborted).toBe(false);

    unmount();
    expect(signal?.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => pendingResponse.resolve(jsonResponse({}, 401)));

    expect(onSessionExpired).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("aborts a read after five seconds and recovers through a manual retry", async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementationOnce(
        (_input, init) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener(
              "abort",
              () => reject(new DOMException("Request aborted", "AbortError")),
              { once: true },
            );
          }),
      )
      .mockResolvedValueOnce(jsonResponse(range([reading(71.5)])));
    const onSessionExpired = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={onSessionExpired}
        now={() => NOW}
      />,
    );
    const signal = fetchMock.mock.calls[0]?.[1]?.signal;

    await act(async () => vi.advanceTimersByTimeAsync(4_999));
    expect(signal?.aborted).toBe(false);
    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading recent telemetry",
    );
    expect(
      screen.getByRole("button", { name: "Refresh telemetry" }),
    ).toBeDisabled();
    await act(async () => vi.advanceTimersByTimeAsync(1));
    expect(signal?.aborted).toBe(true);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "could not load recent telemetry",
    );
    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onSessionExpired).not.toHaveBeenCalled();
    vi.useRealTimers();

    fireEvent.click(screen.getByRole("button", { name: "Retry telemetry" }));
    expect(await screen.findByRole("table")).toHaveTextContent(
      formattedCelsius(71.5),
    );
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("uses a new rolling window for refresh and replaces readings after refresh failure and recovery", async () => {
    const refreshResponse = deferred<Response>();
    const nextWindow = {
      from: "2026-08-14T12:05:00.000Z",
      to: "2026-08-15T12:05:00.000Z",
    };
    const now = vi.fn(() => NOW);
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse(range([reading(71.5)])))
      .mockReturnValueOnce(refreshResponse.promise)
      .mockResolvedValueOnce(
        jsonResponse({ ...range([reading(83.25)]), ...nextWindow }),
      );
    vi.stubGlobal("fetch", fetchMock);
    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={now}
      />,
    );
    expect(await screen.findByRole("table")).toHaveTextContent(
      formattedCelsius(71.5),
    );
    now.mockReturnValue(new Date(nextWindow.to));

    fireEvent.click(screen.getByRole("button", { name: "Refresh telemetry" }));
    const refreshedUrl = new URL(
      String(fetchMock.mock.calls[1]?.[0]),
      "http://localhost",
    );
    expect(Object.fromEntries(refreshedUrl.searchParams)).toEqual({
      ...nextWindow,
      limit: "100",
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Loading recent telemetry",
    );
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
    await act(async () => refreshResponse.resolve(jsonResponse({}, 503)));
    expect(screen.getByRole("alert")).toHaveTextContent(
      "could not load recent telemetry",
    );
    expect(screen.queryByRole("table")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Retry telemetry" }));
    const table = await screen.findByRole("table");
    expect(table).toHaveTextContent(formattedCelsius(83.25));
    expect(table).not.toHaveTextContent(formattedCelsius(71.5));
    expect(table.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(now).toHaveBeenCalledTimes(3);
  });

  it("rejects malformed readings without rendering their values and can retry", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        jsonResponse(
          range([{ ...reading(71.5), value: "private-invalid-value" }]),
        ),
      )
      .mockResolvedValueOnce(jsonResponse(range([reading(83.25)])));
    vi.stubGlobal("fetch", fetchMock);
    render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={() => {}}
        now={() => NOW}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load recent telemetry",
    );
    expect(screen.queryByText(/private-invalid-value/)).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByRole("img")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Retry telemetry" }));
    expect(await screen.findByRole("table")).toHaveTextContent(
      formattedCelsius(83.25),
    );
  });

  it.each([
    {
      name: "equal times and values",
      readings: [reading(71.5, 1), reading(71.5, 2)],
      expectedX: [320, 320],
      expectedY: [110, 110],
    },
    {
      name: "submillisecond times and minimum-step values",
      readings: [
        reading(-0.000001, 1, "2026-08-15T11:15:00.000001Z"),
        reading(0, 2, "2026-08-15T11:15:00.000002Z"),
        reading(0.000001, 3, "2026-08-15T11:15:00.000003Z"),
      ],
      expectedX: null,
      expectedY: [186, 110, 34],
    },
    {
      name: "negative, six-place and extreme values",
      readings: [
        reading(-1_000_000_000_000, 1, "2026-08-15T11:15:00Z"),
        reading(-1.234567, 2, "2026-08-15T11:16:00Z"),
        reading(1.234567, 3, "2026-08-15T11:17:00Z"),
        reading(1_000_000_000_000, 4, "2026-08-15T11:18:00Z"),
      ],
      expectedX: [34, 34 + 572 / 3, 34 + (572 * 2) / 3, 606],
      expectedY: null,
    },
  ])(
    "preserves table order and finite chart points for $name",
    async ({ readings, expectedX, expectedY }) => {
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>(async () => jsonResponse(range(readings))),
      );
      render(
        <TelemetryPanel
          sensors={[SENSOR]}
          onSessionExpired={() => {}}
          now={() => NOW}
        />,
      );

      const table = await screen.findByRole("table");
      const rows = Array.from(table.querySelectorAll("tbody tr"));
      expect(rows).toHaveLength(readings.length);
      rows.forEach((row, index) => {
        expect(row.querySelector("time")).toHaveAttribute(
          "datetime",
          readings[index].observedAt,
        );
        expect(row.querySelector("data")).toHaveAttribute(
          "value",
          String(readings[index].value),
        );
        expect(row.querySelector("data")?.textContent).toBe(
          formattedCelsius(readings[index].value),
        );
      });
      const chart = screen.getByRole("img", { name: /Recent readings/ });
      const points = Array.from(
        chart.querySelectorAll(".telemetry-chart__point"),
      );
      expect(points).toHaveLength(readings.length);
      let previousX = 34;
      points.forEach((point, index) => {
        const x = Number(point.getAttribute("cx"));
        const y = Number(point.getAttribute("cy"));
        expect(Number.isFinite(x)).toBe(true);
        expect(x).toBeGreaterThanOrEqual(previousX);
        expect(x).toBeLessThanOrEqual(606);
        previousX = x;
        if (expectedX !== null) expect(x).toBeCloseTo(expectedX[index]);
        expect(Number.isFinite(y)).toBe(true);
        expect(y).toBeGreaterThanOrEqual(34);
        expect(y).toBeLessThanOrEqual(186);
        if (expectedY !== null) expect(y).toBeCloseTo(expectedY[index]);
      });
    },
  );

  it("delegates an expired session and handles no configured sensor", async () => {
    const onSessionExpired = vi.fn();
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse({}, 401));
    vi.stubGlobal("fetch", fetchMock);
    const { rerender } = render(
      <TelemetryPanel
        sensors={[SENSOR]}
        onSessionExpired={onSessionExpired}
        now={() => NOW}
      />,
    );

    await waitFor(() => expect(onSessionExpired).toHaveBeenCalledOnce());

    rerender(
      <TelemetryPanel
        sensors={[]}
        onSessionExpired={onSessionExpired}
        now={() => NOW}
      />,
    );
    expect(
      screen.getByText(
        "Telemetry is unavailable until a sensor is configured.",
      ),
    ).toBeVisible();
  });
});
