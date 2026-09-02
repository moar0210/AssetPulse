-- Read-only evidence for the production telemetry range query.
-- Required psql variables:
--   organisation_id  UUID belonging to the inspected tenant
--   sensor_id        UUID belonging to the same tenant
--   from             inclusive ISO-8601 timestamp with offset
--   to               exclusive ISO-8601 timestamp with offset
--   limit            requested row count; clamped to the API range 1..500
--
-- EXPLAIN ANALYZE executes the SELECT and can consume meaningful database resources.
-- Run it during a controlled window with a read-only database role. The transaction,
-- lock timeout, and statement timeout prevent this artifact from modifying data or
-- waiting without a bound.

\set ON_ERROR_STOP on

BEGIN TRANSACTION READ ONLY;
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15s';

EXPLAIN (ANALYZE, BUFFERS, COSTS, SETTINGS, SUMMARY, TIMING)
SELECT id, value, observed_at
FROM (
    SELECT id, value, observed_at
    FROM telemetry_reading
    WHERE organisation_id = :'organisation_id'::uuid
      AND sensor_id = :'sensor_id'::uuid
      AND observed_at >= :'from'::timestamptz
      AND observed_at < :'to'::timestamptz
    ORDER BY observed_at DESC, id DESC
    LIMIT LEAST(GREATEST(:'limit'::integer, 1), 500)
) recent_readings
ORDER BY observed_at, id;

ROLLBACK;
