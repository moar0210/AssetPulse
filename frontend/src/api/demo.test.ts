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
      expect(body.idempotencyKey).toBe(
        "dashboard:overheating:20260831T180142000Z",
      );
      expect(body.readings.map((reading) => reading.observedAt)).toEqual([
        "2026-08-31T17:55:00.000Z",
        "2026-08-31T17:56:00.000Z",
        "2026-08-31T17:57:00.000Z",
        "2026-08-31T17:58:00.000Z",
        "2026-08-31T17:59:00.000Z",
        "2026-08-31T18:00:00.000Z",
      ]);
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

  it.each([
    "not-a-date",
    "-271821-04-20T00:00:00.000Z",
    "-000001-12-31T23:59:59.999Z",
    "0000-01-01T00:00:00Z",
    "0000-01-01T00:05:59.999Z",
    "0000-01-01T01:05:59.999+01:00",
    "9999-12-31T23:00:00-01:00",
    "+010000-01-01T00:00:00Z",
    "+010000-01-01T00:01:00Z",
    "+275760-09-13T00:00:00.000Z",
  ])("rejects unsupported scenario time %s before fetching", async (time) => {
    const fetchMock = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetchMock);

    await expect
      .soft(launchOverheatingScenario(csrfToken, undefined, new Date(time)))
      .rejects.toThrow("A valid scenario time is required");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    {
      time: "0000-01-01T00:06:00Z",
      first: "0000-01-01T00:00:00.000Z",
      last: "0000-01-01T00:05:00.000Z",
      key: "dashboard:overheating:00000101T000600000Z",
    },
    {
      time: "0000-01-01T01:06:00+01:00",
      first: "0000-01-01T00:00:00.000Z",
      last: "0000-01-01T00:05:00.000Z",
      key: "dashboard:overheating:00000101T000600000Z",
    },
    {
      time: "0000-01-01T00:06:59.999Z",
      first: "0000-01-01T00:00:00.000Z",
      last: "0000-01-01T00:05:00.000Z",
      key: "dashboard:overheating:00000101T000659999Z",
    },
    {
      time: "9999-12-31T23:59:59.999Z",
      first: "9999-12-31T23:53:00.000Z",
      last: "9999-12-31T23:58:00.000Z",
      key: "dashboard:overheating:99991231T235959999Z",
    },
  ])(
    "preserves bounded readings at $time",
    async ({ time, first, last, key }) => {
      const fetchMock = vi.fn<typeof fetch>(async (_input, init) => {
        const body = JSON.parse(String(init?.body)) as {
          idempotencyKey: string;
        };
        return jsonResponse({
          batchId: "70000000-0000-0000-0000-000000000001",
          idempotencyKey: body.idempotencyKey,
          readingCount: 6,
          acceptedAt: "2026-08-31T18:00:00Z",
        });
      });
      vi.stubGlobal("fetch", fetchMock);

      await launchOverheatingScenario(csrfToken, undefined, new Date(time));
      await launchOverheatingScenario(csrfToken, undefined, new Date(time));

      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(fetchMock.mock.calls[1]?.[1]?.body).toBe(
        fetchMock.mock.calls[0]?.[1]?.body,
      );
      const body = JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body)) as {
        idempotencyKey: string;
        readings: Array<{ observedAt: string }>;
      };
      expect(body.idempotencyKey).toBe(key);
      expect(body.readings).toHaveLength(6);
      expect(body.readings[0]?.observedAt).toBe(first);
      expect(body.readings[5]?.observedAt).toBe(last);
      expect(
        body.readings.map((reading) => Date.parse(reading.observedAt)),
      ).toEqual(
        Array.from(
          { length: 6 },
          (_, index) => Date.parse(first) + index * 60_000,
        ),
      );
      expect(Date.parse(last)).toBeLessThan(new Date(time).getTime());
    },
  );

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
