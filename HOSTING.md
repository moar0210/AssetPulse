# Zero-Cost Hosting Plan

AssetPulse Lite will be published as a portfolio demonstration without a payment method, paid subscription, paid trial, or automatic overage commitment. Public deployment is a `v0.7` outcome. It is not claimed as complete today.

## Selected topology

The primary plan is:

```text
Browser and simulator
        |
        | HTTPS, one origin
        v
Render Free Docker web service
  - Nginx serves the React build
  - Nginx proxies /api and SSE
  - Spring Boot listens on loopback
        |
        | verified TLS with an application-only role
        v
Neon Free PostgreSQL
```

One composite web service preserves the existing same-origin session cookie, CSRF, and SSE contracts. It also avoids consuming the free web-service allowance with a second application process. PostgreSQL remains the durable source of truth; the application container stores no durable files.

The repository prepares this topology but does not create external resources. `render.yaml` selects the free plan and leaves all database values for manual secret entry. The hosting dashboard must receive only a least-privilege application role, never a database owner credential.

## Why this is the default

- Render documents a no-payment path for a free web service and supports Docker, managed HTTPS, health checks, Git-linked deployments, and rollback to recent deploys.
- Neon documents a `$0` plan with no time limit and no credit card requirement.
- One public origin keeps the current security model intact without adding CORS or weakening cookie policy.
- The container remains portable. A future no-card host can use the same image, injected port, health endpoint, and public smoke contract.

## Current free-tier constraints

Terms were checked on 2026-09-05 and must be checked again immediately before resource creation.

| Service                 | Current free boundary                                                                                                                        | Consequence for this project                                                                                                                                                                                                                 |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Render Free web service | 0.1 CPU, 512 MB RAM, 750 instance-hours per workspace each month, idle sleep after 15 minutes, roughly one-minute wake, ephemeral filesystem | Cap JVM memory and threads, keep the JDBC pool small, expect cold starts, and store durable data only in PostgreSQL. Without a payment method, quota exhaustion suspends or disables service activity instead of creating an overage charge. |
| Neon Free PostgreSQL    | 100 CU-hours per project each month, 0.5 GB storage per project, scale-to-zero after inactivity, no time limit, no credit card               | Keep demo data bounded, do not run the simulator continuously, expect a database wake on the first query, and document quota suspension honestly.                                                                                            |
| AssetPulse runtime      | Session and SSE subscriber state is process-local                                                                                            | A sleep or deploy can require sign-in again. EventSource reconnect always refreshes authoritative PostgreSQL-backed state.                                                                                                                   |

Render's free PostgreSQL offer is not used because its database expires after 30 days. AssetPulse also makes no uptime, high-availability, production-scale, or zero-cold-start claim.

## Deployment contract for v0.7

The [operations runbook](infra/operations/RUNBOOK.md) gives the deployment,
evidence-capture, incident, and rollback procedure.

Before `v0.7` can close:

1. Recheck that both selected plans still require no card, subscription, paid trial, or automatic billing.
2. Create a Neon Free project and a SQL-created least-privilege application role.
3. Store the following only in Render's secret settings:
   - `SPRING_DATASOURCE_URL`, using the direct Neon endpoint and `sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca-certificates.crt&channelBinding=require`;
   - `SPRING_DATASOURCE_USERNAME`;
   - `SPRING_DATASOURCE_PASSWORD`.
4. Create the free Render service from `render.yaml` and confirm that deployment waits for the four GitHub checks.
5. Run the repository's public smoke verifier and the live-incident browser journey against the generated HTTPS URL.
6. Record the deployed commit, cold-start behavior, limits, logs, metrics, trace, scans, performance evidence, and rollback rehearsal.

Stop before resource creation if either provider asks for payment details or enables billable usage. Do not publish a public URL until status, authentication, tenant denial, logout, and the live incident have passed against that exact revision.

Both database clients include the distribution CA bundle. The JDBC URL selects
that bundle explicitly, and the bootstrap image defaults `PGSSLROOTCERT` to the
same path. Keep certificate and host-name verification enabled; the clients do
not automatically use the system bundle when only `verify-full` is supplied.

## No-card alternatives

These are fallbacks, not parallel deployment work:

| Option                                                                                               | Strength                                                                                                          | Trade-off                                                                                                                  |
| ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Cloudflare Pages and a Pages Function in front of Render Free and Neon Free                          | Static interface loads without waiting for the Java service; the proxy can retain a same-origin browser contract. | Adds a third service and proxy code. Cookie forwarding, CSRF, and SSE streaming require their own deployed smoke evidence. |
| Render Free static site plus Render Free backend and Neon Free                                       | Uses one hosting vendor for the frontend and API.                                                                 | Splits the runtime, exposes a separate backend address, and needs proof that the static rewrite preserves SSE and cookies. |
| Cloudflare Pages or GitHub Pages as a static case study, with Docker Compose for the runnable system | Remains publicly inspectable even if no suitable dynamic no-card host is available.                               | It is not a live backend demo and cannot satisfy the v1.0 public-journey criterion; the release must say so explicitly.    |
| Back4app free container plus Neon Free                                                               | Advertised as no-card container hosting with more CPU than Render's free service.                                 | Its 256 MB memory limit is a poor fit for the current Java runtime and is only viable after a measured memory experiment.  |

Platforms that require a card, pre-authorization, paid workspace, or automatic billing are outside this project's hosting policy even when they advertise a free allowance.

## Official references

- [Render free services and limits](https://render.com/docs/free)
- [Render Blueprint schema](https://render.com/docs/blueprint-spec)
- [Render no-payment deployment guide](https://render.com/docs/your-first-deploy)
- [Render compute plans](https://render.com/docs/compute-plans)
- [Neon pricing and Free plan](https://neon.com/pricing)
- [Cloudflare Pages limits](https://developers.cloudflare.com/pages/platform/limits/)
- [Cloudflare Pages Functions pricing](https://developers.cloudflare.com/pages/functions/pricing/)
- [Cloudflare streaming responses](https://developers.cloudflare.com/workers/runtime-apis/streams/)
- [Back4app container pricing](https://www.back4app.com/pricing/container-as-a-service)
