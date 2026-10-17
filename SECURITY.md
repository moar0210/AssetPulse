# Security model and limitations

AssetPulse is a portfolio demonstration using public seeded accounts and
synthetic data. Do not store equipment data, personal information or production
credentials in it. A visitor can use the same demo identity as another visitor.

## Trust boundaries

The browser and simulator are untrusted API clients. Spring Security establishes
identity and loads organisation/role context from the database. Client fields,
headers and hidden controls do not grant access. Tenant-owned SQL requires the
trusted organisation, with relational ownership constraints as an additional
boundary. Missing and foreign resources use non-leaking responses.

Nginx exposes one origin for the client, API and SSE. The hosted backend listens
on loopback. Hosted PostgreSQL uses a constrained application login, certificate and
hostname verification, and required channel binding. Deployment credentials are
supplied through host settings and are absent from the repository.

## Controls and verification

| Risk | Control | Evidence entry point |
| --- | --- | --- |
| Tenant or role escalation | Trusted session actor, scoped queries, role and technician ownership checks | `PermissionMatrixTest`, `WorkOrderApiIntegrationTest`, isolation browser journey |
| Session fixation and CSRF | Rotated session ID, HttpOnly/SameSite=Strict cookie with Secure required for hosted HTTPS, 30-minute timeout, mutation CSRF and logout invalidation | `SessionSecurityAssuranceTest`, public smoke |
| Credential guessing | Generic failures, bounded per-email/client-address throttling, validated proxy address boundary | `LoginFailureLimiterTest`, `LoginClientAddressResolverTest` |
| Partial or repeated telemetry | Bounded validation, scoped idempotency fingerprint, atomic batch/readings/processing intent | Telemetry ingestion and transaction integration tests |
| Duplicate or stale processing | PostgreSQL leases, token fencing, transactional effects, bounded retry and dead state | Worker, lifecycle and restart integration tests |
| Lost updates or forged history | Expected-version transitions, ownership, atomic history/audit and immutability constraints | Work-order and audit integration tests; concurrency journey |
| Stream disclosure | Tenant scope, originating-session revocation, minimal invalidation events, authoritative reads | Alert stream security, session and expiry tests |
| Data disclosure in errors/logs | Stable problem responses, correlation IDs, allowlisted log/trace fields, no public actuator | API failure, request-log and span-exporter tests |
| Content injection | React text rendering, same-origin CSP, frame denial, no-sniff and referrer/feature headers | Document-header checks and browser journeys |
| Unsafe database bootstrap | Administrator-role/membership rejection and certificate verification | Bootstrap/TLS smoke scripts and container verification |

CI checks dependencies, the hosting image and detected secrets. Critical findings
block acceptance. Any scoped non-applicability disposition is documented under
[`infra/security`](infra/security/CVE-2026-47890.md) with regression checks;
it is not a blanket scanner suppression or a claim of no vulnerabilities.

Local Compose defaults to a non-Secure cookie for HTTP development. Public HTTPS
hosting requires `ASSETPULSE_SESSION_COOKIE_SECURE=true`.
The isolated local Compose database network uses unencrypted PostgreSQL transport;
the certificate and channel-binding requirements apply to the hosted connection.

## Residual risks

- Login throttling is per email/address pair, not global admission. Anonymous
  CSRF requests can allocate expiring sessions; there is no session-count quota.
- Telemetry and audit accumulate. Reset preserves durable history and cooldowns.
  Monitor free storage/compute allowances and stop demonstration traffic before
  exhausting them.
- Sessions, throttling and SSE subscribers are process-local. The deployment
  supports one instance, and sleep or restart may require signing in again.
- Stream lifetime is bounded, but subscriber count is not. Revocation drops
  queued updates; an already-admitted socket write may finish asynchronously.
- The application login also runs Flyway and owns its schema. It is not a
  separate DML-only runtime identity, and database-owner compromise is outside
  tenant API protections.
- Compatible application rollback preserves the database. It does not reverse
  migrations or demonstrate backup restoration.

Named automated checks and dated scan reports are evidence of the exercised
boundaries, not a penetration test or proof that every possible defect is absent.
See [architecture](ARCHITECTURE.md) and the
[operations runbook](infra/operations/RUNBOOK.md) for implementation and recovery.
