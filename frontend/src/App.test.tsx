import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import App from "./App";

const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-1",
} as const;

const identity = {
  userId: "10000000-0000-0000-0000-000000000001",
  displayName: "Nora Admin",
  email: "admin@northstar.example",
  organisation: {
    id: "00000000-0000-0000-0000-000000000001",
    slug: "northstar-operations",
    name: "Northstar Operations",
  },
  role: {
    code: "OPERATIONS_ADMIN",
    displayName: "Operations Admin",
  },
} as const;

const assets = [
  {
    id: "20000000-0000-0000-0000-000000000001",
    assetCode: "PUMP-101",
    name: "Boiler Feed Pump",
  },
  {
    id: "20000000-0000-0000-0000-000000000002",
    assetCode: "PUMP-102",
    name: "Cooling Water Pump",
  },
] as const;

const dashboardSummary = {
  assetCount: assets.length,
  openAlertCount: 0,
  activeWorkOrderCount: 0,
  recentActivity: [],
} as const;

const assetDetail = {
  ...assets[0],
  sensors: [
    {
      id: "30000000-0000-0000-0000-000000000001",
      sensorKey: "PUMP-101-TEMP",
      name: "Pump casing temperature",
      measurementType: "TEMPERATURE",
      unit: "CELSIUS",
      thresholdRules: [
        {
          id: "40000000-0000-0000-0000-000000000001",
          ruleCode: "PUMP-101-HIGH-TEMP",
          name: "High temperature",
          comparison: "GREATER_THAN_OR_EQUAL_TO",
          thresholdValue: 95,
          cooldownSeconds: 300,
          enabled: true,
        },
      ],
    },
  ],
} as const;

const alertId = "50000000-0000-0000-0000-000000000001";
const alertContext = {
  asset: assets[0],
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
const alertSummary = {
  id: alertId,
  status: "OPEN",
  occurrenceCount: 3,
  lastOccurredAt: "2026-08-23T10:03:00Z",
  context: alertContext,
} as const;
const openAlertDetail = {
  ...alertSummary,
  firstOccurredAt: "2026-08-23T10:01:00Z",
  cooldownUntil: "2026-08-23T10:08:00Z",
  createdAt: "2026-08-23T10:01:01Z",
  updatedAt: "2026-08-23T10:03:01Z",
  history: [],
} as const;
const acknowledgedAlertDetail = {
  ...openAlertDetail,
  status: "ACKNOWLEDGED",
  updatedAt: "2026-08-23T10:04:00Z",
  history: [
    {
      sequenceNumber: 1,
      fromStatus: "OPEN",
      toStatus: "ACKNOWLEDGED",
      actor: {
        id: identity.userId,
        displayName: identity.displayName,
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

class ControllableEventSource {
  readonly close = vi.fn();
  readonly url: string;
  readonly eventSourceInit: EventSourceInit;
  private readonly listeners = new Map<string, Set<EventListener>>();

  constructor(url: string, eventSourceInit: EventSourceInit) {
    this.url = url;
    this.eventSourceInit = eventSourceInit;
  }

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

function installEventSource() {
  const sources: ControllableEventSource[] = [];
  class EventSourceStub extends ControllableEventSource {
    constructor(url: string | URL, eventSourceInit: EventSourceInit = {}) {
      super(String(url), eventSourceInit);
      sources.push(this);
    }
  }
  vi.stubGlobal("EventSource", EventSourceStub);
  return sources;
}

function installFetch(
  handler: (url: string, init: RequestInit | undefined) => Promise<Response>,
  assetHandler: (
    init: RequestInit | undefined,
  ) => Promise<Response> = async () => jsonResponse({ assets }),
) {
  const fetchMock = vi.fn<typeof fetch>((input, init) => {
    const url = String(input);
    if (url === "/api/v1/dashboard") {
      return Promise.resolve(jsonResponse(dashboardSummary));
    }

    if (url === "/api/v1/assets") {
      return assetHandler(init);
    }

    if (url.startsWith("/api/v1/sensors/")) {
      const parsedUrl = new URL(url, "http://localhost");
      const sensorId = parsedUrl.pathname.split("/")[4];
      return Promise.resolve(
        jsonResponse({
          sensorId,
          from: parsedUrl.searchParams.get("from"),
          to: parsedUrl.searchParams.get("to"),
          readings: [],
        }),
      );
    }

    return handler(url, init);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

async function openAssets() {
  fireEvent.click(await screen.findByRole("button", { name: "Assets" }));
}

function statusResponse() {
  return jsonResponse({ status: "available" });
}

describe("seeded session application", () => {
  it("announces loading while discovering the current session", () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }

      return new Promise<Response>(() => {});
    });

    render(<App />);

    expect(screen.getByRole("status")).toHaveTextContent(
      "Checking your session",
    );
    expect(screen.queryByRole("button", { name: "Sign in" })).toBeNull();
  });

  it("shows seeded login guidance for an anonymous session", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      return jsonResponse({}, 401);
    });

    render(<App />);

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeEnabled();
    expect(screen.getByLabelText("Email")).toHaveAttribute(
      "autocomplete",
      "username",
    );
    expect(screen.getByText("admin@northstar.example")).toBeInTheDocument();
    expect(screen.getByText("AssetPulse1!")).toBeInTheDocument();
    expect(await screen.findByText("available")).toBeInTheDocument();
  });

  it("lands on the dashboard and preserves organisation assets behind navigation", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      return jsonResponse(identity);
    });

    render(<App />);

    expect(
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeInTheDocument();
    expect(screen.getByText("admin@northstar.example")).toBeInTheDocument();
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    expect(screen.getByText("Operations Admin")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Dashboard", current: "page" }),
    ).toBeVisible();
    expect(screen.getByRole("heading", { name: "Dashboard" })).toBeVisible();

    await openAssets();
    expect(
      await screen.findByRole("heading", { name: "Assets" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Boiler Feed Pump")).toBeInTheDocument();
    expect(screen.getByText("PUMP-101")).toBeInTheDocument();
    expect(screen.getByText("Cooling Water Pump")).toBeInTheDocument();
  });

  it("moves focus to the current Alerts navigation after a scenario launch", async () => {
    installEventSource();
    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === "/api/v1/alerts?limit=50") {
        return jsonResponse({ alerts: [], limit: 50 });
      }
      if (url === "/api/v1/telemetry-batches" && init?.method === "POST") {
        const request = JSON.parse(String(init.body)) as {
          idempotencyKey: string;
          readings: readonly unknown[];
        };
        return jsonResponse({
          batchId: "70000000-0000-0000-0000-000000000001",
          idempotencyKey: request.idempotencyKey,
          readingCount: request.readings.length,
          acceptedAt: "2026-09-01T00:00:00Z",
        });
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Launch and open alerts" }),
    );

    const alertsNavigation = await screen.findByRole("button", {
      name: "Alerts",
      current: "page",
    });
    await waitFor(() => expect(alertsNavigation).toHaveFocus());
    expect(screen.getByRole("heading", { name: "Alerts" })).toBeVisible();
  });

  it("opens the authenticated alert queue and wires App-held CSRF to commands", async () => {
    const sources = installEventSource();
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === "/api/v1/alerts?limit=50") {
        return jsonResponse({ alerts: [alertSummary], limit: 50 });
      }
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        expect(init?.method).toBe("POST");
        return jsonResponse(acknowledgedAlertDetail);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        return jsonResponse(openAlertDetail);
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Alerts" }));
    expect(
      await screen.findByRole("heading", { name: "Alerts" }),
    ).toBeVisible();
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    expect(await screen.findByText("Alert acknowledged.")).toBeVisible();
    const commandCall = fetchMock.mock.calls.find(
      ([url]) => url === `/api/v1/alerts/${alertId}/acknowledge`,
    );
    expect(commandCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-1" }),
    );
    expect(sources).toHaveLength(1);
    expect(sources[0]?.url).toBe("/api/v1/alerts/stream");
    expect(sources[0]?.eventSourceInit).toEqual({ withCredentials: true });

    fireEvent.click(screen.getByRole("button", { name: "Assets" }));
    expect(
      await screen.findByRole("heading", { name: "Assets" }),
    ).toBeVisible();
    expect(sources[0]?.close).toHaveBeenCalledOnce();
  });

  it("rediscovers authoritative session and CSRF after command verification fails", async () => {
    const sources = installEventSource();
    let csrfRequests = 0;
    let sessionReads = 0;
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (url === "/api/v1/session") {
        sessionReads += 1;
        return jsonResponse(identity);
      }
      if (url === "/api/v1/alerts?limit=50") {
        return jsonResponse({ alerts: [alertSummary], limit: 50 });
      }
      if (url === `/api/v1/alerts/${alertId}/acknowledge`) {
        return problemResponse("CSRF_REJECTED", 403);
      }
      if (url === `/api/v1/alerts/${alertId}`) {
        return jsonResponse(openAlertDetail);
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Alerts" }));
    fireEvent.click(
      await screen.findByRole("button", {
        name: /view open alert for boiler feed pump/i,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Acknowledge alert" }),
    );

    await waitFor(() => {
      expect(csrfRequests).toBe(2);
      expect(sessionReads).toBe(2);
    });
    expect(
      screen.getByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Dashboard", current: "page" }),
    ).toBeVisible();
    expect(sources[0]?.close).toHaveBeenCalledOnce();

    const commandCall = fetchMock.mock.calls.find(
      ([url]) => url === `/api/v1/alerts/${alertId}/acknowledge`,
    );
    expect(commandCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-1" }),
    );
  });

  it("shows asset loading without hiding trusted identity or sign-out", async () => {
    installFetch(
      async (url) => {
        if (url === "/api/v1/status") {
          return statusResponse();
        }
        if (url === "/api/v1/session/csrf") {
          return jsonResponse(csrfToken);
        }
        return jsonResponse(identity);
      },
      async () => new Promise<Response>(() => {}),
    );

    render(<App />);

    expect(
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeInTheDocument();
    await openAssets();
    expect(screen.getByRole("status")).toHaveTextContent("Loading assets");
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("shows an empty organisation asset list", async () => {
    installFetch(
      async (url) => {
        if (url === "/api/v1/status") {
          return statusResponse();
        }
        if (url === "/api/v1/session/csrf") {
          return jsonResponse(csrfToken);
        }
        return jsonResponse(identity);
      },
      async () => jsonResponse({ assets: [] }),
    );

    render(<App />);

    await openAssets();
    expect(await screen.findByText("No assets are available.")).toBeVisible();
    expect(screen.getByLabelText("Asset count")).toHaveTextContent("0");
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("retries an unavailable asset list while preserving the session", async () => {
    let assetRequests = 0;
    installFetch(
      async (url) => {
        if (url === "/api/v1/status") {
          return statusResponse();
        }
        if (url === "/api/v1/session/csrf") {
          return jsonResponse(csrfToken);
        }
        return jsonResponse(identity);
      },
      async () => {
        assetRequests += 1;
        return assetRequests === 1
          ? jsonResponse({}, 503)
          : jsonResponse({ assets });
      },
    );

    render(<App />);

    await openAssets();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load assets",
    );
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Retry assets" }));

    expect(await screen.findByText("Boiler Feed Pump")).toBeInTheDocument();
    expect(assetRequests).toBe(2);
  });

  it("opens exact read-only sensor and threshold configuration and navigates back", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === `/api/v1/assets/${assetDetail.id}`) {
        return jsonResponse(assetDetail);
      }
      return jsonResponse(identity);
    });

    render(<App />);
    await openAssets();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );

    expect(
      await screen.findByRole("heading", { name: "Boiler Feed Pump" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Read-only")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Sensor configuration" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Pump casing temperature" }),
    ).toBeInTheDocument();
    expect(screen.getByText("PUMP-101-TEMP")).toBeInTheDocument();
    expect(screen.getByText("Celsius (°C)")).toBeInTheDocument();
    expect(screen.getByText("High temperature")).toBeInTheDocument();
    expect(screen.getByText(/At or above/)).toHaveTextContent("95 °C");
    expect(screen.getByText("300 seconds")).toBeInTheDocument();
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Back to assets" }));

    expect(
      await screen.findByRole("heading", { name: "Assets" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    ).toBeEnabled();
    expect(screen.queryByText("PUMP-101-TEMP")).toBeNull();
  });

  it("shows asset-detail loading while preserving trusted session controls", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === `/api/v1/assets/${assetDetail.id}`) {
        return new Promise<Response>(() => {});
      }
      return jsonResponse(identity);
    });

    render(<App />);
    await openAssets();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Loading asset details",
    );
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
    expect(
      screen.getByRole("button", { name: "Back to assets" }),
    ).toBeEnabled();
  });

  it("retries an unavailable asset detail without discarding identity", async () => {
    let detailRequests = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === `/api/v1/assets/${assetDetail.id}`) {
        detailRequests += 1;
        return detailRequests === 1
          ? jsonResponse({}, 503)
          : jsonResponse(assetDetail);
      }
      return jsonResponse(identity);
    });

    render(<App />);
    await openAssets();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load this asset",
    );
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    fireEvent.click(
      screen.getByRole("button", { name: "Retry asset details" }),
    );

    expect(
      await screen.findByRole("heading", { name: "Boiler Feed Pump" }),
    ).toBeInTheDocument();
    expect(detailRequests).toBe(2);
  });

  it("renders a generic non-leaking asset not-found state", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === `/api/v1/assets/${assetDetail.id}`) {
        return jsonResponse(
          {
            code: "ASSET_NOT_FOUND",
            detail: "Foreign compressor belongs to Riverside",
          },
          404,
        );
      }
      return jsonResponse(identity);
    });

    render(<App />);
    await openAssets();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );

    expect(
      await screen.findByRole("heading", { name: "Asset not found" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "requested asset is not available",
    );
    expect(screen.queryByText(/Foreign compressor|Riverside/)).toBeNull();
    expect(screen.queryByText("PUMP-101")).toBeNull();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();
  });

  it("rediscovers the session and clears identity after an asset-list 401", async () => {
    let sessionReads = 0;
    installFetch(
      async (url) => {
        if (url === "/api/v1/status") {
          return statusResponse();
        }
        if (url === "/api/v1/session/csrf") {
          return jsonResponse(csrfToken);
        }
        sessionReads += 1;
        return sessionReads === 1
          ? jsonResponse(identity)
          : jsonResponse({}, 401);
      },
      async () => jsonResponse({}, 401),
    );

    render(<App />);
    await openAssets();

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeEnabled();
    expect(screen.queryByText("Welcome, Nora Admin")).toBeNull();
    expect(screen.queryByText("Northstar Operations")).toBeNull();
    expect(sessionReads).toBe(2);
  });

  it("rediscovers the session and clears detail after an asset-detail 401", async () => {
    let sessionReads = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === `/api/v1/assets/${assetDetail.id}`) {
        return jsonResponse({}, 401);
      }
      sessionReads += 1;
      return sessionReads === 1
        ? jsonResponse(identity)
        : jsonResponse({}, 401);
    });

    render(<App />);
    await openAssets();
    fireEvent.click(
      await screen.findByRole("button", {
        name: "View details for Boiler Feed Pump (PUMP-101)",
      }),
    );

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeEnabled();
    expect(screen.queryByText("Welcome, Nora Admin")).toBeNull();
    expect(screen.queryByText("PUMP-101")).toBeNull();
    expect(sessionReads).toBe(2);
  });

  it("signs in with only credentials and refreshes the CSRF token", async () => {
    let csrfRequests = 0;
    let authenticated = false;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (init?.method === "POST") {
        authenticated = true;
        return jsonResponse(identity);
      }
      return authenticated ? jsonResponse(identity) : jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: " admin@northstar.example " },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "AssetPulse1!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeInTheDocument();
    expect(csrfRequests).toBe(2);

    const loginCall = fetchMock.mock.calls.find(
      ([url, init]) => url === "/api/v1/session" && init?.method === "POST",
    );
    expect(loginCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-1" }),
    );
    expect(loginCall?.[1]?.body).toBe(
      JSON.stringify({
        email: "admin@northstar.example",
        password: "AssetPulse1!",
      }),
    );
  });

  it("refreshes an expired anonymous session and retries login once", async () => {
    let csrfRequests = 0;
    let loginRequests = 0;
    let authenticated = false;

    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (init?.method === "POST") {
        loginRequests += 1;
        if (loginRequests === 1) {
          return jsonResponse({}, 403);
        }
        authenticated = true;
        return jsonResponse(identity);
      }
      return authenticated ? jsonResponse(identity) : jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "admin@northstar.example" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "AssetPulse1!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeInTheDocument();
    expect(loginRequests).toBe(2);
    expect(csrfRequests).toBe(3);
  });

  it("rediscovers identity when the login response is lost", async () => {
    let csrfRequests = 0;
    let authenticated = false;
    let loginRequests = 0;

    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (init?.method === "POST") {
        loginRequests += 1;
        authenticated = true;
        throw new TypeError("Connection closed after the request completed");
      }
      return authenticated ? jsonResponse(identity) : jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "admin@northstar.example" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "AssetPulse1!" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" }),
    ).toBeInTheDocument();
    expect(loginRequests).toBe(1);
    expect(csrfRequests).toBe(2);
  });

  it("shows one generic login failure and clears the password", async () => {
    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (init?.method === "POST") {
        return jsonResponse({ detail: "Account does not exist" }, 401);
      }
      return jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });

    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "missing@example.com" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "incorrect" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Email or password is incorrect.",
    );
    expect(screen.queryByText("Account does not exist")).toBeNull();
    expect(screen.getByLabelText("Password")).toHaveValue("");
  });

  it("stops login recovery on throttling and shows only a bounded retry hint", async () => {
    let loginRequests = 0;
    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (init?.method === "POST") {
        loginRequests += 1;
        return new Response(
          JSON.stringify({
            code: "LOGIN_RATE_LIMITED",
            detail: "Email-specific internal limiter state",
          }),
          {
            status: 429,
            headers: {
              "Content-Type": "application/problem+json",
              "Retry-After": "120",
            },
          },
        );
      }
      return jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });
    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "missing@example.com" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "incorrect" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many sign-in attempts. Try again in 120 seconds.",
    );
    expect(screen.queryByText(/internal limiter state/i)).toBeNull();
    expect(screen.getByLabelText("Password")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled();
    expect(loginRequests).toBe(1);
  });

  it("signs out with CSRF, refreshes the token, and returns to login", async () => {
    let csrfRequests = 0;
    let loggedOut = false;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (init?.method === "DELETE") {
        loggedOut = true;
        return new Response(null, { status: 204 });
      }
      return loggedOut ? jsonResponse({}, 401) : jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(csrfRequests).toBe(2);
    const logoutCall = fetchMock.mock.calls.find(
      ([url, init]) => url === "/api/v1/session" && init?.method === "DELETE",
    );
    expect(logoutCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-1" }),
    );
  });

  it("recovers when the server session expires before logout", async () => {
    let csrfRequests = 0;
    let sessionReads = 0;
    let logoutRequests = 0;

    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        csrfRequests += 1;
        return jsonResponse({
          ...csrfToken,
          token: `csrf-token-${csrfRequests}`,
        });
      }
      if (init?.method === "DELETE") {
        logoutRequests += 1;
        return jsonResponse({}, 403);
      }

      sessionReads += 1;
      return sessionReads === 1
        ? jsonResponse(identity)
        : jsonResponse({}, 401);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeInTheDocument();
    expect(logoutRequests).toBe(1);
    expect(csrfRequests).toBe(2);
  });

  it("stops showing trusted identity when logout cannot be confirmed", async () => {
    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (init?.method === "DELETE") {
        return jsonResponse({}, 503);
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load your session",
    );
    expect(screen.queryByText("Welcome, Nora Admin")).toBeNull();
  });

  it("bounds a stalled login attempt", async () => {
    installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (init?.method === "POST") {
        return new Promise<Response>((_, reject) => {
          if (init.signal?.aborted) {
            reject(new DOMException("Aborted", "AbortError"));
            return;
          }
          init.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      }
      return jsonResponse({}, 401);
    });

    render(<App />);
    await screen.findByRole("button", { name: "Sign in" });
    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "admin@northstar.example" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "AssetPulse1!" },
    });

    vi.useFakeTimers();
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await act(async () => vi.advanceTimersByTimeAsync(5_000));
    vi.useRealTimers();

    expect(screen.getByRole("alert")).toHaveTextContent(
      "could not load your session",
    );
  });

  it("shows an unavailable state and recovers on retry", async () => {
    let unavailable = true;
    installFetch(async (url) => {
      if (url === "/api/v1/status") {
        return unavailable ? jsonResponse({}, 503) : statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return unavailable ? jsonResponse({}, 503) : jsonResponse(csrfToken);
      }
      return jsonResponse({}, 401);
    });

    render(<App />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load your session",
    );
    unavailable = false;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Sign in" })).toBeEnabled(),
    );
    expect(await screen.findByText("available")).toBeInTheDocument();
  });
});

describe("operations workspace navigation", () => {
  const processingEventId = "60000000-0000-0000-0000-000000000001";
  const deadProcessingEvent = {
    id: processingEventId,
    telemetryBatchId: "70000000-0000-0000-0000-000000000001",
    eventType: "TELEMETRY_BATCH_ACCEPTED",
    attemptCount: 5,
    createdAt: "2026-08-23T10:00:00Z",
    deadAt: "2026-08-23T10:05:00Z",
    updatedAt: "2026-08-23T10:05:01Z",
    lastErrorCode: "PROCESSING_FAILED",
    lastErrorMessage: "Processing failed; another attempt may be scheduled.",
  } as const;
  const auditEvent = {
    id: "a0000000-0000-0000-0000-000000000001",
    actor: { id: identity.userId, displayName: identity.displayName },
    action: "AUTHENTICATION_SUCCEEDED",
    subject: { type: "USER", id: identity.userId },
    occurredAt: "2026-08-28T10:00:00Z",
    correlationId: "b0000000-0000-0000-0000-000000000001",
  } as const;

  it("shows Operations to an admin and wires App-held CSRF to a bodyless retry", async () => {
    let queueReads = 0;
    const fetchMock = installFetch(async (url, init) => {
      if (url === "/api/v1/status") {
        return statusResponse();
      }
      if (url === "/api/v1/session/csrf") {
        return jsonResponse(csrfToken);
      }
      if (url === "/api/v1/processing-events/dead?limit=50") {
        queueReads += 1;
        return jsonResponse({
          events: queueReads === 1 ? [deadProcessingEvent] : [],
          limit: 50,
        });
      }
      if (url === "/api/v1/audit-events?limit=50") {
        return jsonResponse({ events: [auditEvent], limit: 50 });
      }
      if (url === `/api/v1/processing-events/${processingEventId}/retry`) {
        expect(init?.method).toBe("POST");
        return new Response(null, { status: 204 });
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Operations" }));
    expect(
      await screen.findByRole("heading", { name: "Processing operations" }),
    ).toBeVisible();
    const auditTable = await screen.findByRole("table", {
      name: "Latest organisation audit events (up to 50)",
    });
    expect(within(auditTable).getByText("Signed in")).toBeVisible();
    fireEvent.click(
      await screen.findByRole("button", {
        name: `View dead processing event ${processingEventId}`,
      }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry processing event" }),
    );

    expect(await screen.findByText(/Retry accepted/)).toBeVisible();
    const retryCall = fetchMock.mock.calls.find(
      ([url]) => url === `/api/v1/processing-events/${processingEventId}/retry`,
    );
    expect(retryCall?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token-1" }),
    );
    expect(retryCall?.[1]?.body).toBeUndefined();
    expect(auditTable).toBeVisible();
    expect(
      fetchMock.mock.calls.filter(
        ([url]) => url === "/api/v1/audit-events?limit=50",
      ),
    ).toHaveLength(1);
  });

  it("does not expose Operations navigation or request AUD-01 audit data for technicians or viewers", async () => {
    const restrictedRoles = [
      { code: "TECHNICIAN", displayName: "Technician" },
      { code: "VIEWER", displayName: "Viewer" },
    ] as const;

    for (const role of restrictedRoles) {
      const fetchMock = installFetch(async (url) => {
        if (url === "/api/v1/status") {
          return statusResponse();
        }
        if (url === "/api/v1/session/csrf") {
          return jsonResponse(csrfToken);
        }
        return jsonResponse({ ...identity, role });
      });

      const rendered = render(<App />);
      await screen.findByRole("heading", { name: "Welcome, Nora Admin" });
      expect(screen.queryByRole("button", { name: "Operations" })).toBeNull();
      expect(screen.queryByRole("heading", { name: "Audit trail" })).toBeNull();
      expect(
        fetchMock.mock.calls.some(([url]) =>
          String(url).startsWith("/api/v1/audit-events"),
        ),
      ).toBe(false);
      rendered.unmount();
    }
  });

  it("keeps AUD-01 audit refresh independent while processing recovery remains locked", async () => {
    let queueReads = 0;
    let auditReads = 0;
    let retryCalls = 0;
    installFetch(async (url) => {
      if (url === "/api/v1/status") return statusResponse();
      if (url === "/api/v1/session/csrf") return jsonResponse(csrfToken);
      if (url === "/api/v1/processing-events/dead?limit=50") {
        queueReads += 1;
        return queueReads === 1
          ? jsonResponse({ events: [deadProcessingEvent], limit: 50 })
          : jsonResponse({}, 503);
      }
      if (url === `/api/v1/processing-events/${processingEventId}/retry`) {
        retryCalls += 1;
        return jsonResponse({}, 409);
      }
      if (url === "/api/v1/audit-events?limit=50") {
        auditReads += 1;
        return jsonResponse({
          events: auditReads === 1 ? [auditEvent] : [],
          limit: 50,
        });
      }
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Operations" }));
    await screen.findByText("Signed in");
    fireEvent.click(
      await screen.findByRole("button", {
        name: `View dead processing event ${processingEventId}`,
      }),
    );
    fireEvent.click(
      screen.getByRole("button", { name: "Retry processing event" }),
    );
    expect(
      await screen.findByRole("button", { name: "Latest state required" }),
    ).toBeDisabled();

    fireEvent.click(
      screen.getByRole("button", { name: "Refresh audit trail" }),
    );
    expect(
      await screen.findByText(
        "No audit events have been recorded for your organisation.",
      ),
    ).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Latest state required" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Back to operations" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: "Retry latest state" }),
    ).toBeEnabled();
    expect(queueReads).toBe(2);
    expect(auditReads).toBe(2);
    expect(retryCalls).toBe(1);
  });

  it("keeps processing operations available when AUD-01 audit access is denied", async () => {
    installFetch(async (url) => {
      if (url === "/api/v1/status") return statusResponse();
      if (url === "/api/v1/session/csrf") return jsonResponse(csrfToken);
      if (url === "/api/v1/processing-events/dead?limit=50") {
        return jsonResponse({ events: [deadProcessingEvent], limit: 50 });
      }
      if (url === "/api/v1/audit-events?limit=50") return jsonResponse({}, 403);
      return jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Operations" }));
    expect(
      await screen.findByRole("heading", { name: "Audit access denied" }),
    ).toBeVisible();
    fireEvent.click(
      await screen.findByRole("button", {
        name: `View dead processing event ${processingEventId}`,
      }),
    );
    expect(
      screen.getByRole("button", { name: "Retry processing event" }),
    ).toBeEnabled();
  });

  it("returns to sign-in when an AUD-01 read detects session expiry", async () => {
    let expired = false;
    const fetchMock = installFetch(async (url) => {
      if (url === "/api/v1/status") return statusResponse();
      if (url === "/api/v1/session/csrf") return jsonResponse(csrfToken);
      if (url === "/api/v1/processing-events/dead?limit=50") {
        return jsonResponse({ events: [], limit: 50 });
      }
      if (url === "/api/v1/audit-events?limit=50") {
        expired = true;
        return jsonResponse({}, 401);
      }
      return expired ? jsonResponse({}, 401) : jsonResponse(identity);
    });

    render(<App />);
    fireEvent.click(await screen.findByRole("button", { name: "Operations" }));
    expect(
      await screen.findByRole("button", { name: "Sign in" }),
    ).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Audit trail" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Operations" })).toBeNull();
    expect(
      fetchMock.mock.calls.filter(
        ([url]) => url === "/api/v1/audit-events?limit=50",
      ),
    ).toHaveLength(1);
    expect(
      fetchMock.mock.calls.some(([, init]) => init?.method === "POST"),
    ).toBe(false);
  });
});
