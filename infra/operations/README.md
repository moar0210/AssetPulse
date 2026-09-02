# Operational evidence queries

`telemetry-range-explain.sql` captures an executed query plan for the exact bounded
telemetry-range access pattern used by the API. It contains only a `SELECT`, runs in
a read-only transaction, clamps the limit to 1–500, and sets short lock and statement
timeouts.

Use a read-only database credential and supply psql variables separately from the
SQL file:

```bash
psql "$DATABASE_URL" \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 \
  --set=organisation_id="00000000-0000-0000-0000-000000000001" \
  --set=sensor_id="30000000-0000-0000-0000-000000000001" \
  --set=from="2026-09-01T08:00:00Z" \
  --set=to="2026-09-01T09:00:00Z" \
  --set=limit="100" \
  --file=infra/operations/telemetry-range-explain.sql
```

Record the deployment revision, database environment, UTC capture time, parameter
window, and full plan output. Inspect whether the plan uses
`ix_telemetry_reading_organisation_sensor_observed_id`; if it does not, retain the
row estimates and actual counts and investigate statistics/cardinality without
forcing an index. Because `ANALYZE` executes the query and warms database buffers,
run this evidence check only during a controlled low-traffic window. Do not include
`DATABASE_URL` or credentials in the captured report, and redact tenant identifiers
before sharing the plan outside the engineering team.
