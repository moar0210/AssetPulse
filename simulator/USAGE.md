# Telemetry simulator

The simulator sends deterministic readings through the same public HTTP contract used by other clients. It does not connect to PostgreSQL.

Start the local Compose stack, then run either scenario with Node 24:

```text
npm run normal
npm run overheating
```

Run a scenario at a fixed, non-future instant to make repeated commands exact idempotent retries:

```text
npm run normal -- --at=2026-08-15T11:55:00Z
npm run overheating -- --at=2026-08-15T11:55:00Z
```

The instant must include `Z` or a numeric UTC offset. Fractional seconds, when supplied, may contain at most three digits.

Each scenario sends six readings one minute apart, ending at the anchor. After conversion to UTC, the anchor must be at least `0000-01-01T00:05:00Z` and no later than `9999-12-31T23:59:59.999Z`, so every reading stays within the API's supported calendar years. Future anchors and anchors outside this range are rejected before signing in or sending readings.

The default target is `http://localhost:8080` with the seeded Northstar Operations Admin. Override `ASSETPULSE_BASE_URL`, `ASSETPULSE_EMAIL`, or `ASSETPULSE_PASSWORD` when needed. The base URL must be an `http` or `https` origin without credentials, a path, a query, or a fragment. The selected account must be a Northstar Operations Admin because telemetry ingestion is role- and organisation-scoped.

Run the local checks with:

```text
npm run check
npm test
```
