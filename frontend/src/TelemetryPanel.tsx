import { useEffect, useId, useState } from "react";

import type { Sensor } from "./api/assets";
import {
  TelemetrySensorNotFoundError,
  TelemetrySessionExpiredError,
  getTelemetryReadings,
} from "./api/telemetry";
import type { TelemetryRange, TelemetryReading } from "./api/telemetry";

type TelemetryState =
  | Readonly<{ kind: "no-sensor" }>
  | Readonly<{ kind: "loading"; sensorId: string }>
  | Readonly<{ kind: "ready"; sensorId: string; range: TelemetryRange }>
  | Readonly<{ kind: "not-found"; sensorId: string }>
  | Readonly<{ kind: "unavailable"; sensorId: string }>;

const API_TIMEOUT_MS = 5_000;
const RANGE_MS = 24 * 60 * 60 * 1_000;
const RANGE_LIMIT = 100;
const CHART_WIDTH = 640;
const CHART_HEIGHT = 220;
const CHART_PADDING = 34;

function currentDate() {
  return new Date();
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
    timeZone: "UTC",
  }).format(new Date(value));
}

function formatValue(value: number) {
  return new Intl.NumberFormat(undefined, {
    maximumFractionDigits: 6,
  }).format(value);
}

function chartPoints(readings: readonly TelemetryReading[]) {
  const firstObservedAt = Date.parse(readings[0].observedAt);
  const lastObservedAt = Date.parse(readings[readings.length - 1].observedAt);
  const observedDuration = lastObservedAt - firstObservedAt;
  const values = readings.map(({ value }) => value);
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);
  const valueRange = maximum - minimum;

  return readings.map((reading) => {
    const elapsed = Date.parse(reading.observedAt) - firstObservedAt;
    const x =
      observedDuration === 0
        ? CHART_WIDTH / 2
        : CHART_PADDING +
          (elapsed / observedDuration) * (CHART_WIDTH - CHART_PADDING * 2);
    const y =
      valueRange === 0
        ? CHART_HEIGHT / 2
        : CHART_HEIGHT -
          CHART_PADDING -
          ((reading.value - minimum) / valueRange) *
            (CHART_HEIGHT - CHART_PADDING * 2);

    return { reading, x, y };
  });
}

function TelemetryChart({
  sensor,
  range,
}: Readonly<{ sensor: Sensor; range: TelemetryRange }>) {
  const titleId = useId();
  const descriptionId = useId();
  const points = chartPoints(range.readings);
  const values = range.readings.map(({ value }) => value);
  const minimum = Math.min(...values);
  const maximum = Math.max(...values);

  return (
    <figure className="telemetry-chart">
      <svg
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        role="img"
        aria-labelledby={`${titleId} ${descriptionId}`}
      >
        <title id={titleId}>Recent readings for {sensor.name}</title>
        <desc id={descriptionId}>
          {range.readings.length} chronological temperature readings. Minimum{" "}
          {formatValue(minimum)} degrees Celsius and maximum{" "}
          {formatValue(maximum)} degrees Celsius.
        </desc>
        <line
          className="telemetry-chart__axis"
          x1={CHART_PADDING}
          y1={CHART_HEIGHT - CHART_PADDING}
          x2={CHART_WIDTH - CHART_PADDING}
          y2={CHART_HEIGHT - CHART_PADDING}
        />
        <line
          className="telemetry-chart__axis"
          x1={CHART_PADDING}
          y1={CHART_PADDING}
          x2={CHART_PADDING}
          y2={CHART_HEIGHT - CHART_PADDING}
        />
        <polyline
          className="telemetry-chart__line"
          points={points.map(({ x, y }) => `${x},${y}`).join(" ")}
        />
        {points.map(({ reading, x, y }) => (
          <circle
            className="telemetry-chart__point"
            key={reading.id}
            cx={x}
            cy={y}
            r="5"
          />
        ))}
        <text x={CHART_PADDING} y="22">
          {formatValue(maximum)} °C
        </text>
        <text x={CHART_PADDING} y={CHART_HEIGHT - 8}>
          {formatValue(minimum)} °C
        </text>
      </svg>
    </figure>
  );
}

export function TelemetryPanel({
  sensors,
  onSessionExpired,
  now = currentDate,
}: Readonly<{
  sensors: readonly Sensor[];
  onSessionExpired: () => void;
  now?: () => Date;
}>) {
  const sectionTitleId = useId();
  const tableCaptionId = useId();
  const [selectedSensorId, setSelectedSensorId] = useState(
    sensors[0]?.id ?? "",
  );
  const [telemetryState, setTelemetryState] = useState<TelemetryState>(() => {
    const initialSensorId = sensors[0]?.id;
    return initialSensorId === undefined
      ? { kind: "no-sensor" }
      : { kind: "loading", sensorId: initialSensorId };
  });
  const [loadAttempt, setLoadAttempt] = useState(0);
  const selectedSensor =
    sensors.find(({ id }) => id === selectedSensorId) ?? sensors[0];
  const activeSensorId = selectedSensor?.id;
  const visibleTelemetryState: TelemetryState =
    activeSensorId === undefined
      ? { kind: "no-sensor" }
      : telemetryState.kind !== "no-sensor" &&
          telemetryState.sensorId === activeSensorId
        ? telemetryState
        : { kind: "loading", sensorId: activeSensorId };

  useEffect(() => {
    if (activeSensorId === undefined) {
      setTelemetryState({ kind: "no-sensor" });
      return;
    }

    let active = true;
    const controller = new AbortController();
    const timeoutId = window.setTimeout(
      () => controller.abort(),
      API_TIMEOUT_MS,
    );
    const to = now();
    const from = new Date(to.getTime() - RANGE_MS);

    setTelemetryState({ kind: "loading", sensorId: activeSensorId });

    void getTelemetryReadings(
      activeSensorId,
      from.toISOString(),
      to.toISOString(),
      RANGE_LIMIT,
      controller.signal,
    )
      .then((range) => {
        if (active && !controller.signal.aborted) {
          setTelemetryState({ kind: "ready", sensorId: activeSensorId, range });
        }
      })
      .catch((error: unknown) => {
        if (!active) {
          return;
        }

        if (error instanceof TelemetrySessionExpiredError) {
          onSessionExpired();
          return;
        }

        setTelemetryState(
          error instanceof TelemetrySensorNotFoundError
            ? { kind: "not-found", sensorId: activeSensorId }
            : { kind: "unavailable", sensorId: activeSensorId },
        );
      })
      .finally(() => window.clearTimeout(timeoutId));

    return () => {
      active = false;
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [activeSensorId, loadAttempt, now, onSessionExpired]);

  return (
    <section className="telemetry-section" aria-labelledby={sectionTitleId}>
      <div className="telemetry-heading">
        <div>
          <h3 id={sectionTitleId}>Recent telemetry</h3>
          <p>Last 24 hours · up to 100 most recent readings</p>
        </div>
        {selectedSensor !== undefined && (
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            disabled={visibleTelemetryState.kind === "loading"}
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Refresh telemetry
          </button>
        )}
      </div>

      {sensors.length > 1 && (
        <label className="telemetry-selector">
          <span>Sensor</span>
          <select
            value={selectedSensor?.id ?? ""}
            onChange={(event) => setSelectedSensorId(event.target.value)}
          >
            {sensors.map((sensor) => (
              <option key={sensor.id} value={sensor.id}>
                {sensor.name}
              </option>
            ))}
          </select>
        </label>
      )}

      {visibleTelemetryState.kind === "no-sensor" && (
        <p className="asset-message">
          Telemetry is unavailable until a sensor is configured.
        </p>
      )}

      {visibleTelemetryState.kind === "loading" && (
        <p className="asset-message" role="status" aria-live="polite">
          Loading recent telemetry…
        </p>
      )}

      {visibleTelemetryState.kind === "not-found" && (
        <p className="asset-message" role="status">
          Telemetry for this sensor is not available.
        </p>
      )}

      {visibleTelemetryState.kind === "unavailable" && (
        <div className="telemetry-unavailable">
          <p className="asset-message form-message--error" role="alert">
            We could not load recent telemetry. Try again.
          </p>
          <button
            className="secondary-button secondary-button--compact"
            type="button"
            onClick={() => setLoadAttempt((attempt) => attempt + 1)}
          >
            Retry telemetry
          </button>
        </div>
      )}

      {visibleTelemetryState.kind === "ready" &&
        visibleTelemetryState.range.readings.length === 0 && (
          <p className="asset-message">
            No telemetry readings are available in the last 24 hours.
          </p>
        )}

      {visibleTelemetryState.kind === "ready" &&
        visibleTelemetryState.range.readings.length > 0 &&
        selectedSensor !== undefined && (
          <div className="telemetry-results">
            <TelemetryChart
              sensor={selectedSensor}
              range={visibleTelemetryState.range}
            />
            <div
              className="telemetry-table-scroll"
              role="region"
              aria-labelledby={tableCaptionId}
              tabIndex={0}
            >
              <table className="telemetry-table">
                <caption id={tableCaptionId}>
                  Recent readings for {selectedSensor.name}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Observed (UTC)</th>
                    <th scope="col">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleTelemetryState.range.readings.map((reading) => (
                    <tr key={reading.id}>
                      <td>
                        <time dateTime={reading.observedAt}>
                          {formatTimestamp(reading.observedAt)}
                        </time>
                      </td>
                      <td>
                        <data value={String(reading.value)}>
                          {formatValue(reading.value)} °C
                        </data>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
    </section>
  );
}
