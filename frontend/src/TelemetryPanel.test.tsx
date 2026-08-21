import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

function range(readings: readonly unknown[], sensorId = SENSOR.id) {
  return {
    sensorId,
    from: "2026-08-14T12:00:00.000Z",
    to: "2026-08-15T12:00:00.000Z",
    readings,
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
    const fetchMock = vi.fn<typeof fetch>(async () => jsonResponse(payload));
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
    expect(table).toHaveTextContent("71.5 °C");
    expect(table).toHaveTextContent("83.25 °C");
    expect(table.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(table.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-08-15T11:15:00Z",
    );
    expect(tableRegion).toHaveAttribute("tabindex", "0");

    fireEvent.click(screen.getByRole("button", { name: "Refresh telemetry" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
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
    ).toHaveTextContent("71.5 °C");
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
