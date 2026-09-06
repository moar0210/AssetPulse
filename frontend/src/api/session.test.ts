import { describe, expect, it, vi } from "vitest";

import {
  AuthenticationFailedError,
  getCsrfToken,
  getCurrentSession,
  login,
  LoginRateLimitedError,
  logout,
  RequestVerificationFailedError,
} from "./session";

const csrfToken = {
  headerName: "X-CSRF-TOKEN",
  token: "csrf-token-value",
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

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("session API client", () => {
  it("loads and validates the CSRF header exchange", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(csrfToken));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getCsrfToken()).resolves.toEqual(csrfToken);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/session/csrf",
      expect.objectContaining({
        method: "GET",
        credentials: "same-origin",
      }),
    );
  });

  it("rejects extra CSRF response fields at the API boundary", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(jsonResponse({ ...csrfToken, organisationId: "x" })),
    );

    await expect(getCsrfToken()).rejects.toThrow("unexpected payload");
  });

  it("treats a 401 current-session response as anonymous", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 401)),
    );

    await expect(getCurrentSession()).resolves.toBeNull();
  });

  it("loads a strictly typed authenticated identity", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(identity)),
    );

    await expect(getCurrentSession()).resolves.toEqual(identity);
  });

  it("rejects an unknown role and unexpected identity fields", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          ...identity,
          tenantId: identity.organisation.id,
          role: { code: "OWNER", displayName: "Owner" },
        }),
      ),
    );

    await expect(getCurrentSession()).rejects.toThrow(
      "unexpected identity payload",
    );
  });

  it("submits only credentials with the returned CSRF header", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(identity));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      login(
        {
          email: "admin@northstar.example",
          password: "AssetPulse1!",
        },
        csrfToken,
      ),
    ).resolves.toEqual(identity);

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/session", {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-CSRF-TOKEN": "csrf-token-value",
      },
      credentials: "same-origin",
      body: JSON.stringify({
        email: "admin@northstar.example",
        password: "AssetPulse1!",
      }),
      signal: undefined,
    });
  });

  it("uses one generic typed failure for invalid credentials", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 401)),
    );

    await expect(
      login({ email: "missing@example.com", password: "incorrect" }, csrfToken),
    ).rejects.toBeInstanceOf(AuthenticationFailedError);
  });

  it("classifies login throttling and exposes a bounded delta-seconds hint", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ code: "LOGIN_RATE_LIMITED" }), {
          status: 429,
          headers: {
            "Content-Type": "application/problem+json",
            "Retry-After": "900",
          },
        }),
      ),
    );

    const error = await login(
      { email: "missing@example.com", password: "incorrect" },
      csrfToken,
    ).catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(LoginRateLimitedError);
    expect((error as LoginRateLimitedError).retryAfterSeconds).toBe(900);
  });

  it.each(["0", "86401", "1.5", "tomorrow", null])(
    "does not expose an invalid or excessive Retry-After value (%s)",
    async (retryAfter) => {
      const headers = new Headers({
        "Content-Type": "application/problem+json",
      });
      if (retryAfter !== null) {
        headers.set("Retry-After", retryAfter);
      }
      vi.stubGlobal(
        "fetch",
        vi.fn<typeof fetch>().mockResolvedValue(
          new Response(JSON.stringify({ code: "LOGIN_RATE_LIMITED" }), {
            status: 429,
            headers,
          }),
        ),
      );

      const error = await login(
        { email: "missing@example.com", password: "incorrect" },
        csrfToken,
      ).catch((reason: unknown) => reason);

      expect(error).toBeInstanceOf(LoginRateLimitedError);
      expect((error as LoginRateLimitedError).retryAfterSeconds).toBeNull();
    },
  );

  it("classifies rejected CSRF verification for session mutations", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({}, 403)),
    );

    await expect(
      login(
        { email: "admin@northstar.example", password: "AssetPulse1!" },
        csrfToken,
      ),
    ).rejects.toBeInstanceOf(RequestVerificationFailedError);
    await expect(logout(csrfToken)).rejects.toBeInstanceOf(
      RequestVerificationFailedError,
    );
  });

  it("requires a 204 logout and sends the CSRF header", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(logout(csrfToken)).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/session", {
      method: "DELETE",
      headers: {
        Accept: "application/json",
        "X-CSRF-TOKEN": "csrf-token-value",
      },
      credentials: "same-origin",
      signal: undefined,
    });
  });

  it("rejects a successful-looking response with the wrong media type", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify(identity), {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      ),
    );

    await expect(getCurrentSession()).rejects.toThrow(
      "unexpected content type",
    );
  });
});
