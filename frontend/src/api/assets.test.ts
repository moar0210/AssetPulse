import { describe, expect, it, vi } from "vitest";

import { getAssets, MAX_ASSETS } from "./assets";

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
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

describe("assets API client", () => {
  it("loads a strictly typed bounded asset list", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ assets }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAssets()).resolves.toEqual(assets);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/assets", {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
      signal: undefined,
    });
  });

  it("accepts an exact empty list", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ assets: [] })),
    );

    await expect(getAssets()).resolves.toEqual([]);
  });

  it("rejects unexpected response and item fields", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          assets: [{ ...assets[0], organisationId: "foreign" }],
          total: 1,
        }),
      ),
    );

    await expect(getAssets()).rejects.toThrow("unexpected payload");
  });

  it.each([
    [{ ...assets[0], id: "not-a-uuid" }],
    [{ ...assets[0], assetCode: " PUMP-101" }],
    [{ ...assets[0], name: "" }],
  ])("rejects an invalid asset summary", async (asset) => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(jsonResponse({ assets: [asset] })),
    );

    await expect(getAssets()).rejects.toThrow("unexpected payload");
  });

  it("rejects a list beyond the backend maximum", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          jsonResponse({ assets: Array(MAX_ASSETS + 1).fill(assets[0]) }),
        ),
    );

    await expect(getAssets()).rejects.toThrow("unexpected payload");
  });

  it("rejects unsuccessful and non-JSON responses", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({}, 503))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ assets }), {
          status: 200,
          headers: { "Content-Type": "text/plain" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAssets()).rejects.toThrow("unsuccessful response");
    await expect(getAssets()).rejects.toThrow("unexpected content type");
  });
});
