import { describe, expect, it, vi } from "vitest";

import { getApiStatus } from "./status";

const signal = new AbortController().signal;

describe("status API client", () => {
  it("accepts a JSON media type with case differences and parameters", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ status: "available" }), {
          status: 200,
          headers: { "Content-Type": "Application/JSON; Charset=UTF-8" },
        }),
      ),
    );

    await expect(getApiStatus(signal)).resolves.toEqual({
      status: "available",
    });
  });

  it("rejects an unsuccessful HTTP response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ status: "available" }), {
          status: 503,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(getApiStatus(signal)).rejects.toThrow("unsuccessful response");
  });

  it("rejects a misleading content type", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({ status: "available" }), {
          status: 200,
          headers: { "Content-Type": "application/jsonp" },
        }),
      ),
    );

    await expect(getApiStatus(signal)).rejects.toThrow(
      "unexpected content type",
    );
  });

  it("rejects malformed JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response("{", {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    await expect(getApiStatus(signal)).rejects.toBeInstanceOf(SyntaxError);
  });

  it("propagates a network failure", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockRejectedValue(new TypeError("Network unavailable")),
    );

    await expect(getApiStatus(signal)).rejects.toThrow("Network unavailable");
  });
});
