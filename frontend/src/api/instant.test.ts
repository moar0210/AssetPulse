import { describe, expect, it } from "vitest";

import { parseInstant } from "./instant";

describe("API instant validation", () => {
  it.each([
    null,
    undefined,
    0,
    {},
    "",
    " 2026-01-01T00:00:00Z",
    "2026-01-01T00:00:00Z\n",
    "2026-01-01",
    "2026-01-01T00:00:00",
    "2026-01-01 00:00:00Z",
    "2026-01-01t00:00:00z",
    "2026-02-29T00:00:00Z",
    "1900-02-29T00:00:00Z",
    "2026-04-31T00:00:00Z",
    "2026-00-01T00:00:00Z",
    "2026-13-01T00:00:00Z",
    "2026-01-00T00:00:00Z",
    "2026-01-01T24:00:00Z",
    "2026-01-01T00:60:00Z",
    "2026-01-01T00:00:60Z",
    "2026-01-01T00:00:00.1234567890Z",
    "2026-01-01T00:00:00.Z",
    "2026-01-01T00:00:00+18:01",
    "2026-01-01T00:00:00-19:00",
    "2026-01-01T00:00:00+00:60",
    "+010000-01-01T00:00:00Z",
    "-000001-01-01T00:00:00Z",
  ])("rejects malformed or unsupported instant %j", (value) => {
    expect(parseInstant(value)).toBeNull();
  });

  it.each([
    ["1970-01-01T00:00:00Z", 0n],
    ["1970-01-01T00:00:00.1Z", 100_000_000n],
    ["1969-12-31T23:59:59.999999999Z", -1n],
    ["1970-01-01T00:00:00.000000001Z", 1n],
    ["1970-01-01T18:00:00.000000001+18:00", 1n],
    ["1969-12-31T06:00:00.000000001-18:00", 1n],
    ["1970-01-01T00:00:00-00:00", 0n],
    ["0000-01-01T00:00:00Z", -62_167_219_200_000_000_000n],
    ["9999-12-31T23:59:59.999999999Z", 253_402_300_799_999_999_999n],
  ])("preserves exact nanosecond arithmetic for %s", (value, expected) => {
    expect(parseInstant(value)).toBe(expected);
  });

  it("accepts Gregorian leap years and the existing offset normalization at year edges", () => {
    expect(parseInstant("0000-02-29T00:00:00Z")).not.toBeNull();
    expect(parseInstant("2000-02-29T00:00:00Z")).not.toBeNull();
    expect(parseInstant("0000-01-01T00:00:00+18:00")).toBe(
      parseInstant("0000-01-01T00:00:00Z")! - 64_800_000_000_000n,
    );
    expect(parseInstant("9999-12-31T23:59:59-18:00")).toBe(
      parseInstant("9999-12-31T23:59:59Z")! + 64_800_000_000_000n,
    );
  });
});
