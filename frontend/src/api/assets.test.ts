import { describe, expect, it, vi } from "vitest";

import {
  AssetNotFoundError,
  AssetSessionExpiredError,
  getAssetDetail,
  getAssets,
  MAX_ASSETS,
  MAX_COOLDOWN_SECONDS,
  MAX_THRESHOLD_VALUE,
} from "./assets";

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

  it("loads an exact nested asset configuration", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse(assetDetail));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAssetDetail(assetDetail.id)).resolves.toEqual(assetDetail);
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/assets/${assetDetail.id}`, {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "same-origin",
      signal: undefined,
    });
  });

  it("returns a typed not-found error for the stable 404 boundary", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "ASSET_NOT_FOUND",
            detail: "The requested resource was not found",
          }),
          {
            status: 404,
            headers: { "Content-Type": "application/problem+json" },
          },
        ),
      ),
    );

    await expect(getAssetDetail(assetDetail.id)).rejects.toBeInstanceOf(
      AssetNotFoundError,
    );
  });

  it("distinguishes an expired protected asset session", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({}, 401));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAssets()).rejects.toBeInstanceOf(AssetSessionExpiredError);
    await expect(getAssetDetail(assetDetail.id)).rejects.toBeInstanceOf(
      AssetSessionExpiredError,
    );
  });

  it.each([
    {
      ...assetDetail,
      sensors: [{ ...assetDetail.sensors[0], organisationId: "foreign" }],
    },
    {
      ...assetDetail,
      sensors: [{ ...assetDetail.sensors[0], measurementType: "PRESSURE" }],
    },
    {
      ...assetDetail,
      sensors: [
        {
          ...assetDetail.sensors[0],
          thresholdRules: [
            {
              ...assetDetail.sensors[0].thresholdRules[0],
              internalSensorId: "foreign",
            },
          ],
        },
      ],
    },
    {
      ...assetDetail,
      sensors: [
        {
          ...assetDetail.sensors[0],
          thresholdRules: [
            {
              ...assetDetail.sensors[0].thresholdRules[0],
              thresholdValue: MAX_THRESHOLD_VALUE + 1,
            },
          ],
        },
      ],
    },
    {
      ...assetDetail,
      sensors: [
        {
          ...assetDetail.sensors[0],
          thresholdRules: [
            {
              ...assetDetail.sensors[0].thresholdRules[0],
              cooldownSeconds: MAX_COOLDOWN_SECONDS + 1,
            },
          ],
        },
      ],
    },
  ])("rejects an invalid nested asset configuration", async (payload) => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(jsonResponse(payload)),
    );

    await expect(getAssetDetail(assetDetail.id)).rejects.toThrow(
      "unexpected payload",
    );
  });

  it("rejects a mismatched or invalid asset identifier", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(jsonResponse({ ...assetDetail, id: assets[1].id }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getAssetDetail(assetDetail.id)).rejects.toThrow(
      "unexpected payload",
    );
    await expect(getAssetDetail("not-an-id")).rejects.toThrow(
      "valid asset identifier",
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
