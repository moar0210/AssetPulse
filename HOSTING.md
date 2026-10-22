# Public demo hosting

[Open AssetPulse](https://assetpulse-rv4z.onrender.com). The portfolio demo runs
on one Render Free web service with Neon Free PostgreSQL. It uses synthetic data
and shared seeded accounts. See [local setup and demo accounts](SETUP.md) to run
your own copy or follow the maintenance journey.

See the [release notes](https://github.com/moar0210/AssetPulse/releases) for
released revisions and their verification. The public
[OpenAPI contract](https://assetpulse-rv4z.onrender.com/openapi.json) describes
the deployed API.

The [September](infra/evidence/SEPTEMBER-2026.md) and
[October](infra/evidence/OCTOBER-2026.md) evidence packages record earlier checks,
including clean startup, browser journeys, security, performance and compatible
rollback. Each names the source and deployed revisions it tested. These are
dated observations, not claims about every later deployment.

## Topology

```text
Browser and simulator
        |
        | HTTPS, one origin
        v
Render Free Docker web service
  Nginx serves React and proxies /api and SSE
  Spring Boot listens on loopback
        |
        | verified TLS, constrained application role
        v
Neon Free PostgreSQL
```

PostgreSQL holds durable telemetry, processing events, alerts, work, and audit.
The container keeps no durable files. One origin preserves the session cookie,
CSRF, and authenticated SSE boundary. `render.yaml` selects Free compute and
deployment from `main` after CI checks pass; database values belong only in the
hosting secret settings.

## What to expect

- The free web service sleeps after inactivity. The first status request on
  12 September took about 116 seconds. Wait for the service to wake before
  signing in; this is a single observation, not an uptime guarantee.
- Session, login-throttling, and SSE subscriber state is process-local. After
  sleep or deployment, sign in again. SSE reconnect refreshes database state.
- The deployment supports one application instance. Its image bounds JVM memory,
  threads, connection pool, and worker polling. It has no production-scale claim.
- Keep simulator runs short. Telemetry and audit accumulate; the bounded reset
  finishes active work and resolves alerts while preserving history, readings,
  and cooldowns. It does not reclaim storage.
- Demo accounts are public. Login throttling is per email/client-address pair;
  there is no global admission limit, automatic retention, or lifetime data cap.
  Monitor provider usage and suspend the demo if unexpected traffic threatens
  its free allowance.

The September 7 evidence records the observed free-plan compute/storage limits,
cold and warm timings, a small three-iteration performance run, query plan,
trace, metrics, and durable-state rollback checks. Read the
[evidence package](infra/evidence/SEPTEMBER-2026.md) for the exact revision,
measurements, screenshots, and limitations.

## Maintain the deployment

Follow the [operations runbook](infra/operations/RUNBOOK.md) for database role
bootstrap, certificate verification, deployment identity, public smoke,
incident checks, and a schema-compatible rollback. The hosting application
receives only the constrained database login; never give it the database owner
credential or weaken `verify-full` and required channel binding.

This project does not accept a payment method, paid trial, paid subscription,
or automatic overage commitment. Recheck the official
[Render free-service terms](https://render.com/docs/free) and
[Neon Free plan](https://neon.com/pricing) before creating or changing resources.
Historical measurements do not guarantee current provider quotas. If a provider
stops meeting the no-card requirement, keep the Compose version reproducible
and mark the public demo unavailable while evaluating another no-card host.
