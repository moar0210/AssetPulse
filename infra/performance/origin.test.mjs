import assert from "node:assert/strict";
import test from "node:test";

import { normalizePerformanceOrigin } from "./origin.mjs";

test("normalizes public HTTPS origins without browser URL globals", () => {
  assert.equal(
    normalizePerformanceOrigin("HTTPS://AssetPulse.onrender.com:443/"),
    "https://assetpulse.onrender.com",
  );
  assert.equal(
    normalizePerformanceOrigin("https://example.com:8443"),
    "https://example.com:8443",
  );
  assert.equal(
    normalizePerformanceOrigin("https://192.0.2.1"),
    "https://192.0.2.1",
  );
  assert.equal(
    normalizePerformanceOrigin("https://[2001:db8::1]"),
    "https://[2001:db8::1]",
  );
});

test("permits HTTP only with an explicit loopback override", () => {
  for (const hostname of ["localhost", "127.0.0.1", "[::1]"]) {
    const origin = `http://${hostname}:8080`;
    assert.throws(() => normalizePerformanceOrigin(origin), /HTTPS/);
    assert.equal(
      normalizePerformanceOrigin(origin, { allowHttp: true }),
      origin,
    );
  }
  assert.throws(
    () => normalizePerformanceOrigin("http://example.com", { allowHttp: true }),
    /loopback/,
  );
  assert.throws(
    () =>
      normalizePerformanceOrigin("https://localhost.example.com", {
        allowHttp: true,
      }),
    /loopback/,
  );
});

test("rejects credentials, paths, queries, fragments, and malformed authorities", () => {
  for (const value of [
    undefined,
    "",
    " https://example.com",
    "https://example.com\n",
    "ftp://example.com",
    "https://user:password@example.com",
    "https://example.com/api",
    "https://example.com?token=private",
    "https://example.com#fragment",
    "https://example.com\\@localhost",
    "https://example..com",
    "https://-example.com",
    "https://example.com.",
    "https://127.1",
    "https://256.1.2.3",
    "https://127.00.0.1",
    "https://[:::1]",
    "https://[1:2:3]",
    "https://[1:2:3:4:5:6:7:8:9]",
    "https://example.com:0",
    "https://example.com:65536",
    "https://example.com:abc",
    "https://example.com:99999999999999999999999",
  ]) {
    assert.throws(
      () => normalizePerformanceOrigin(value),
      undefined,
      String(value),
    );
  }
});
