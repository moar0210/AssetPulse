import { describe, expect, it, vi } from "vitest";

import {
  DemoRequestVerificationError,
  DemoResetLimitExceededError,
  launchOverheatingScenario,
  resetDemo,
} from "./demo";

const csrfToken = { headerName: "X-CSRF-TOKEN", token: "csrf-token" } as const;

function jsonResponse(payload: unknown, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function problemResponse(code: string, status: number) {
  return new Response(JSON.stringify({ code }), {
    status,
    headers: { "Content-Type": "application/problem+json" },
  });
}

describe("demo API client", () => {
  it("submits the bounded overheating scenario with in-memory CSRF", async () => {
    const fetchMock = vi.fn<typeof fetch>(async (_input, init) => {
      const body = JSON.parse(String(init?.body)) as {
        idempotencyKey: string;
        readings: Array<{
          sensorId: string;
          value: number;
          observedAt: string;
        }>;
      };
      expect(body.readings.map((reading) => reading.value)).toEqual([
        72, 74, 77.5, 80, 83.5, 86,
      ]);
      expect(
        body.readings.every(
          (reading) =>
            Date.parse(reading.observedAt) <=
            Date.parse("2026-08-31T18:00:00Z"),
        ),
      ).toBe(true);
      return jsonResponse({
        batchId: "70000000-0000-0000-0000-000000000001",
        idempotencyKey: body.idempotencyKey,
        readingCount: 6,
        acceptedAt: "2026-08-31T18:00:00Z",
      });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      launchOverheatingScenario(
        csrfToken,
        undefined,
        new Date("2026-08-31T18:01:42Z"),
      ),
    ).resolves.toMatchObject({ readingCount: 6 });
    expect(fetchMock.mock.calls[0]?.[1]?.headers).toEqual(
      expect.objectContaining({ "X-CSRF-TOKEN": "csrf-token" }),
    );
  });

  it("submits a bodyless reset and validates its bounded result", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({
        resetAt: "2026-08-31T18:00:00Z",
        alertsResolved: 2,
        workOrdersCompleted: 1,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(resetDemo(csrfToken)).resolves.toMatchObject({
      alertsResolved: 2,
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/demo/reset",
      expect.objectContaining({ method: "POST" }),
    );
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty("body");
  });

  it("rejects reset counts above the server safety bound", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(
        jsonResponse({
          resetAt: "2026-08-31T18:00:00Z",
          alertsResolved: 101,
          workOrdersCompleted: 0,
        }),
      ),
    );

    await expect(resetDemo(csrfToken)).rejects.toThrow(
      "The demo command result is uncertain",
    );
  });

  it("distinguishes CSRF rejection and the reset safety limit", async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(problemResponse("CSRF_REJECTED", 403))
      .mockResolvedValueOnce(problemResponse("DEMO_RESET_LIMIT_EXCEEDED", 409));
    vi.stubGlobal("fetch", fetchMock);

    await expect(resetDemo(csrfToken)).rejects.toBeInstanceOf(
      DemoRequestVerificationError,
    );
    await expect(resetDemo(csrfToken)).rejects.toBeInstanceOf(
      DemoResetLimitExceededError,
    );
  });
});
