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

The default target is `http://localhost:8080` with the seeded Northstar Operations Admin. Override `ASSETPULSE_BASE_URL`, `ASSETPULSE_EMAIL`, or `ASSETPULSE_PASSWORD` when needed. The base URL must be an `http` or `https` origin without credentials, a path, a query, or a fragment. The selected account must be a Northstar Operations Admin because telemetry ingestion is role- and organisation-scoped.

Run the local checks with:

```text
npm run check
npm test
```
