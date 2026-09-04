function validHostname(hostname) {
  if (hostname.startsWith("[")) {
    const parts = hostname.slice(1, -1).split("::");
    if (parts.length > 2) {
      return false;
    }
    const groups = parts.flatMap((part) =>
      part === "" ? [] : part.split(":"),
    );
    return (
      groups.every((group) => /^[0-9a-f]{1,4}$/.test(group)) &&
      (parts.length === 2 ? groups.length < 8 : groups.length === 8)
    );
  }

  if (/^[0-9.]+$/.test(hostname)) {
    const octets = hostname.split(".");
    return (
      octets.length === 4 &&
      octets.every(
        (octet) => /^(0|[1-9][0-9]{0,2})$/.test(octet) && Number(octet) <= 255,
      )
    );
  }

  return (
    hostname.length <= 253 &&
    hostname
      .split(".")
      .every((label) => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))
  );
}

export function normalizePerformanceOrigin(value, { allowHttp = false } = {}) {
  if (typeof value !== "string" || value.trim() !== value) {
    throw new Error("ASSETPULSE_BASE_URL must be an absolute origin");
  }

  const match =
    /^(https?):\/\/(\[[a-f0-9:]+\]|[a-z0-9.-]+)(?::([0-9]+))?\/?$/i.exec(value);
  if (match === null || !validHostname(match[2].toLowerCase())) {
    throw new Error(
      "ASSETPULSE_BASE_URL must be a credential-free HTTP(S) origin",
    );
  }

  const protocol = match[1].toLowerCase();
  const hostname = match[2].toLowerCase();
  const port = match[3] === undefined ? null : Number(match[3]);
  if (
    port !== null &&
    (!Number.isSafeInteger(port) || port < 1 || port > 65535)
  ) {
    throw new Error("ASSETPULSE_BASE_URL port must be between 1 and 65535");
  }
  if (protocol !== "https" && !allowHttp) {
    throw new Error("ASSETPULSE_BASE_URL must use HTTPS (HTTP is local-only)");
  }
  if (allowHttp && !["localhost", "127.0.0.1", "[::1]"].includes(hostname)) {
    throw new Error(
      "ASSETPULSE_K6_ALLOW_HTTP may only be used with a loopback origin",
    );
  }

  const defaultPort = protocol === "https" ? 443 : 80;
  const suffix = port === null || port === defaultPort ? "" : `:${port}`;
  return `${protocol}://${hostname}${suffix}`;
}
