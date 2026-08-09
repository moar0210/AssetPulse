import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
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

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function installFetch(
  handler: (url: string, init: RequestInit | undefined) => Promise<Response>,
  assetHandler: (
    init: RequestInit | undefined,
  ) => Promise<Response> = async () => jsonResponse({ assets }),
) {
  const fetchMock = vi.fn<typeof fetch>((input, init) => {
    const url = String(input);
    return url === "/api/v1/assets" ? assetHandler(init) : handler(url, init);
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
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

  it("renders trusted identity and organisation assets after discovery", async () => {
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
      await screen.findByRole("heading", { name: "Assets" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("Boiler Feed Pump")).toBeInTheDocument();
    expect(screen.getByText("PUMP-101")).toBeInTheDocument();
    expect(screen.getByText("Cooling Water Pump")).toBeInTheDocument();
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

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "could not load assets",
    );
    expect(screen.getByText("Northstar Operations")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign out" })).toBeEnabled();

    fireEvent.click(screen.getByRole("button", { name: "Retry assets" }));

    expect(await screen.findByText("Boiler Feed Pump")).toBeInTheDocument();
    expect(assetRequests).toBe(2);
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
