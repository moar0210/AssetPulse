# Public demo operations

This procedure applies to the single Render Free web service and Neon Free
PostgreSQL database. A public deployment is accepted only after the evidence below
passes against its recorded source revision.

## Deploy an accepted revision

1. Confirm that the `Backend`, `Frontend`, `Simulator`, and `Containers` checks
   passed for the pull request and that the change entered protected `main`.
2. Check the current [Render free-service terms](https://render.com/docs/free) and
   [Neon Free plan](https://neon.com/pricing). Keep Render on Hobby with no card on
   file, select Free compute, and keep Neon on Free. Stop if payment details or a
   paid plan are required.
3. Create the Neon project with PostgreSQL 17 in AWS Frankfurt. Leave Neon Auth
   disabled; the application owns its seeded sessions. Use its direct endpoint,
   not the transaction-pooling endpoint.
4. Bootstrap a SQL-created application role using
   `infra/postgres/Dockerfile.bootstrap`. The owner supplies the administrator
   connection and a new application password privately. Pass `PGHOST`, `PGPORT`,
   `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `PGSSLMODE=verify-full`,
   `PGCHANNELBINDING=require`, `ASSETPULSE_PASSWORD_MODE=server-hashed`, `ASSETPULSE_APP_USERNAME`, and
   `ASSETPULSE_APP_PASSWORD` as environment variables. The bootstrap rejects an
   administrator-role collision and privileged role memberships. Never use the
   Neon owner role as the application login. The image installs the distribution
   CA bundle and sets `PGSSLROOTCERT=/etc/ssl/certs/ca-certificates.crt`; preserve
   that trust path with `PGSSLMODE=verify-full` and required channel binding.
   Neon rejects the pre-hashed password sent by psql's default password command.
   The explicit `server-hashed` mode sends the password value only over verified
   TLS for Neon to hash; client output from that statement is suppressed. Use a
   database name rather than a connection string, and leave `PGSERVICE` and
   `PGSERVICEFILE` unset. Local Compose retains the default `client-hashed` mode.
5. Create the Render Blueprint from `render.yaml`. It selects one Free Docker
   service in Frankfurt, `main`, and deployment after checks pass. The owner enters
   only the application values in Render's secret settings:
   `SPRING_DATASOURCE_URL=jdbc:postgresql://<direct-host>/<database>?sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca-certificates.crt&channelBinding=require`,
   `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`.
   The explicit `sslrootcert` selects the image's installed distribution CA
   bundle; PgJDBC otherwise looks for a per-user `root.crt` absent from the image.
6. Verify that the deployed commit matches the accepted `main` revision. The
   startup script takes `RENDER_GIT_COMMIT` as its runtime build identity; structured
   application logs include that value as `service.version`. Locally built images
   also accept `ASSETPULSE_BUILD_REVISION` as a build argument for their OCI label.
7. Keep the public URL pending until status, authentication, isolation, logout,
   SSE reconnect, and the live incident pass. Do not publish credentials, cookie
   values, database endpoints, or unredacted failure artifacts as evidence.

## Verify the public origin

Before deployment, the protected Containers job runs
`bash infra/smoke/database-tls.sh` against both final client images. Its disposable
PostgreSQL fixture verifies trusted TLS with required channel binding and rejects
both an untrusted CA and a mismatched host name. This isolates the trust settings;
the public checks below must still prove the actual Neon connection.

Record the exact revision and UTC time before running these commands. Set
`ASSETPULSE_BASE_URL` to the generated HTTPS origin. First measure one request after
the free service has actually gone idle; record its elapsed time and any provider
wake page separately from the warm checks. Avoid periodic keep-alive traffic.

```bash
node infra/smoke/public-smoke.mjs --base-url "$ASSETPULSE_BASE_URL" --request-timeout-ms 30000
cd frontend
ASSETPULSE_EXPECT_SECURITY_HEADERS=true npm run test:e2e
```

Run both simulator scenarios using the commands in `simulator/USAGE.md`. Run the
bounded performance scenario only after browser activity has ended, following
`infra/performance/README.md`. Save its raw summary, command, region, runtime limits,
revision, and warm/cold state. A failed or partial run is not performance evidence.

In the app, the Northstar Operations Admin selects **Prepare reset**, then
**Confirm reset** under **Reset active demo state**, to
finish at most 100 active alerts and work orders through their legal transitions.
Reset preserves telemetry, history, audit, and alert cooldowns. It is not a storage
purge or a substitute for checking the Neon storage quota. When the free quota is
near exhaustion, stop simulator activity and suspend the demo until a deliberate
data-retention decision is made.

Use synthetic data only. The seeded accounts are shared demo accounts. Login
throttling applies to each email and client-address pair; it does not provide a
global request limit or stop traffic spread across many pairs. Anonymous CSRF
requests can create sessions, and repeated accepted writes grow the database.
There is no lifetime data cap or automatic retention job. Keep demonstration runs
short, check usage before and after them, and suspend the service if unexpected
traffic threatens the free allowance.

## Capture operational evidence

Use Render's log viewer for the accepted deploy; shell access is not available on
Free. Retain a small redacted sample rather than an unrestricted log export.

- `http_request` records identify the correlation, safe route, status, duration,
  and trusted actor/organisation context without request bodies or credentials.
- `operational_metrics` records contain request/error counts and HTTP duration,
  pending-event count, processing lag, retrying count, and dead count. Capture
  these after running the telemetry journey, including the observation time.
- Match the trace ID of `assetpulse.telemetry.accept` to
  `assetpulse.telemetry.process`. Confirm the processing span continues the stored
  context and records an alert effect. Preserve span/parent identifiers and safe
  outcome attributes, not raw readings or session data.
- Retain the dependency, secret, and image scan outputs from the `Containers`
  check for the same revision. Resolve critical findings before acceptance.
- Run the bounded read-only query in `telemetry-range-explain.sql` using the
  procedure in this directory's README. Save the full plan and explain whether
  cardinality justifies an index scan or sequential scan; do not force the index.

Record runtime memory and CPU observations alongside the configured 512 MB,
0.1 CPU, four-connection pool, and five-second worker polling limit. Record
cold-start time and any session loss separately. These measurements describe this
bounded demonstration, not throughput capacity or an availability guarantee.

## Incident checks

If status is unavailable, inspect the current deployment, startup logs, free-hour
allowance, and whether Render is waking or suspended. Do not treat `/api/v1/status`
as database readiness: it confirms that the HTTP process is available. A successful
seeded login and protected read are the database-backed readiness checks.

If an accepted batch has no alert, first confirm it breached the seeded rule and
was not an exact replay or suppressed by the documented cooldown. Inspect pending
count, lag, retries, and safe dead-event details. An Operations Admin can retry an
own-organisation dead event from Operations after the underlying problem is fixed.
Do not insert replacement events or change committed telemetry to bypass retry.

If the browser loses its session after sleep or deployment, sign in again. SSE
reconnect refreshes authoritative database state; it is not durable event replay.
Keep the service at one instance while sessions and subscribers are process-local.

## Roll back and rehearse recovery

Before every deployment, record the current working commit and Render deploy ID.
Free supports rollback only to the two most recent previous deploys. Retain the
last known working revision and verify schema compatibility before selecting it.

1. Stop demo writes. A dashboard rollback disables automatic deployment; an API
   rollback requires disabling it separately so a newer `main` revision cannot
   immediately replace the rollback.
2. In Render's deployment history, select the previous successful compatible
   deploy and use **Rollback**, then **Rollback to this deploy**. Check that the
   target deploy's environment still points to the intended Neon database and
   valid application credential: Render restores that deploy's environment for
   the rollback. Do not delete the database or edit Flyway history.
3. Wait for status and database-backed login/read readiness; rerun public smoke.
   Verify that prior telemetry, work history, and audit remain available. Expect
   process-local sessions to require sign-in again.
4. If migrations are incompatible with the previous application, stop and use a
   forward correction; rolling back application code does not reverse a schema.
5. Restore the accepted revision through a normal checked deployment, rerun smoke
   and the live incident, and restore deployment after checks pass.

Record both deploy IDs/revisions, times, smoke outcomes, and durable-state checks.
Guidance alone does not establish that a rollback rehearsal passed.
