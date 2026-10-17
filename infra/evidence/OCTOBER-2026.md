# Finalization verification: October 2026

Verification on **16 October 2026** covers candidate source
[`939f1de`](https://github.com/moar0210/AssetPulse/commit/939f1de22564ae2f29a7e0621c6279ab7836f0f2)
and, separately, the existing public deployment
[`6c6f800`](https://github.com/moar0210/AssetPulse/commit/6c6f800c589fc25a05d7ff2acc149194c00956fa).
The candidate is not yet merged or deployed. Final README, demonstration media,
release version alignment and v1 publication remain release work.

The [machine-readable verification record](verification-2026-10-16.json)
contains exact revisions, image identity, runtimes, scan scope, startup, clean-clone
and rollback outcomes. The [September package](SEPTEMBER-2026.md) remains dated
historical evidence; its measurements have not been relabeled as current.

## Functional and build checks

| Check | Result |
| --- | --- |
| Java 21/PostgreSQL backend | 353 tests, zero failures/errors/skips; formatting and packaging pass; normal JVM exit |
| Node 24 frontend | 494 tests in 20 files; formatting, lint, strict TypeScript and production build pass |
| Simulator, contract, smoke and performance helpers | 81 tests pass |
| Native vulnerability-policy boundaries | 32 tests pass |
| Fresh local candidate journeys | All three pass: live incident, isolation/role denial and two-client conflict |
| Public deployment journeys | All three pass against `6c6f800` |
| Accessibility and layout | Keyboard/focus checks, serious/critical axe checks and horizontal-overflow checks at desktop and 390px mobile sizes pass |
| Expanded public smoke | All 10 checks pass locally, on the constrained image and on public HTTPS |
| Dependency audit | Zero findings in both full and production-only npm audits |
| Critical source/image and secret gates | Pass with the existing narrowly scoped Spring MVC dispositions |

The image scan's raw findings still include CVE-2026-47884 and CVE-2026-47890 in
Spring MVC 6.2.19. Existing applicability reviews and native policy regressions
justify their scoped dispositions; there are zero active critical findings after
that policy. See the [first review](../security/CVE-2026-47884.md) and
[second review](../security/CVE-2026-47890.md). This is not a claim that the raw
image scan has no findings or that lower-severity findings were all eliminated.
Trivy 0.70.0 used the database updated on 15 October and downloaded on 16 October.

The public web verifier now loads `/` and its built same-origin JavaScript/CSS,
rejecting missing assets, incorrect media types, empty bodies and HTML fallback.
The browser journeys separately execute the application. Focused alert recovery
also passed 16 native HTTP/SSE desktop/mobile fixture cases; those controlled
responses are separate from the real-backend journeys above.

## Reproducibility and database transport

A fresh clone of `939f1de` built and started all three Compose services with new
random local database credentials and a fresh volume. Sixteen migrations seeded
two organisations, four users and three assets. The documented non-Secure local
HTTP cookie setting was retained.

Ordinary `docker compose down` followed by `up --wait` preserved durable row
hashes. An exact telemetry retry after restart returned the original acceptance
and retained six readings and one processing event. The combined candidate's
browser journey also exercised bounded legal reset and retained history/audit.

Both database clients accepted a trusted certificate and rejected an untrusted
CA and a mismatched hostname. Bootstrap transport/password-redaction checks and
repeated TLS bootstrap passed. Running the existing TLS script from Windows
required a local path-conversion wrapper; the production script was unchanged.

## Bounded timing and runtime fit

The local composite image ran non-root with a read-only root filesystem, a
128-process limit, **0.1 CPU and 512 MiB**, a four-connection pool and five-second
worker polling. Status became ready after **136.7 seconds**. An observed memory
sample was **181.1 MiB**; no OOM termination or restart occurred during the check.
These are observations, not peak-memory or capacity guarantees.

Both k6 runs used one virtual user and exactly three iterations:

| Observation | Local constrained image | Public Render deployment |
| --- | ---: | ---: |
| Successful iterations | 3/3 | 3/3 |
| Successful checks | 47/47 | 34/34 |
| Failed HTTP requests | 0/47 | 0/34 |
| Telemetry acceptance p95 | 636.9 ms | 293.2 ms |
| Telemetry-to-alert p95 | 5,782.4 ms | 3,088.6 ms |
| Total duration | 28.364 s | 15.634 s |

The [local raw summary](k6-local-2026-10-16.json) and
[public raw summary](k6-public-2026-10-16.json) retain configuration, metrics and
thresholds. The local report preserves its original run label
`9fb2a6b-finalization-candidate`: that source was hash-verified and then committed
as `939f1de`. The image retained the `finalization-candidate` revision label;
the verification record maps its immutable image ID to that committed source.
Reproduce with the [bounded k6 instructions](../performance/README.md),
using `k6 run infra/performance/telemetry-to-alert.k6.js` and the environment and
revision recorded in the respective report. Both services were awake for these
runs. Three samples do not establish typical latency, throughput or an SLO.

The [first local attempt](k6-local-overlap-2026-10-16.json) failed all three
iterations before telemetry submission because the immediately preceding
simulator had newer observations than the evidence window. After that window
passed, the unchanged script passed with a new run ID. Preserve this failed
attempt: the causal guard worked as intended, and the failed run is not timing
evidence. Finish simulator traffic and allow its latest observation to precede
the k6 window before measuring.

The first public status request on 16 October took **116.4 seconds** and returned
the exact available response. Its preceding idle duration was not established,
so this is not a controlled cold-start measurement.

## Logs, metrics, trace and query

The [sanitized operational record](operational-evidence-2026-10-16.json) includes
safe request/correlation and seeded actor context, request count/error/duration,
pending count, processing lag, retrying count and dead count. Request counters
are cumulative for that process; the maximum duration uses the registry window.
The recorded errors include deliberate denial probes from smoke verification.

One accepted batch continues the same trace through its persisted context:
the processing span's parent is the acceptance span, and committed flow records
show processing start, three alert occurrences and completion. No credentials,
session cookies, request bodies or raw readings are included.

The [read-only query plan](telemetry-range-plan-2026-10-16.txt) used
`ix_telemetry_reading_organisation_sensor_observed_id`, returning 18 rows in
0.106 ms in the fresh local fixture. It used an index-only scan with 18 heap
fetches and a final chronological sort. The query ran under a restricted read
role with the documented transaction/timeouts. Its tiny dataset and warm buffers
do not establish a production performance bound.

## Deployment identity and rollback

GitHub's [deployment record](https://api.github.com/repos/moar0210/AssetPulse/deployments/7050650373/statuses)
reports Render deployment success for `6c6f800` on 13 October. Its
[main verification run](https://github.com/moar0210/AssetPulse/actions/runs/38470285506)
is green. The public smoke and journeys above verify that deployed revision;
they do not claim the new candidate was deployed.

A local previous-image rollback to `6c6f800`, followed by restoration of the
candidate, used the same PostgreSQL database. All 16 migration files were
identical. Telemetry, alert, work-order and audit row hashes matched before/after
each image switch; all 10 smoke checks passed after rollback and restore.
The previous image used one CPU to shorten rehearsal; the restored candidate
retained its measured 0.1-CPU limit. This is application-image compatibility
evidence, distinct from the September provider rollback and from backup restore.

Normal PR review, merge, version-aligned candidate checks, final public artifact
identity and the v1 tag remain the release gate. Shared accounts, process-local
sessions/SSE, free-host sleep and accumulating synthetic history remain the
documented limits in [hosting](../../HOSTING.md),
[architecture](../../ARCHITECTURE.md) and [security](../../SECURITY.md).
