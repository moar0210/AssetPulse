# Telemetry-to-alert performance evidence

`telemetry-to-alert.k6.js` is a deliberately small authenticated check of the public,
same-origin path from telemetry acceptance to alert visibility. It is not a load or
capacity test. The script refuses more than 1 virtual user, 20 total iterations, a
30-second per-request timeout, or a 60-second alert wait.

## Run against a public deployment

Install k6 locally, create an output directory if you choose a nested summary path,
and keep credentials in environment variables rather than command arguments:

The verification workflow checks script initialization in checksum-verified k6
1.7.1. Origin parsing is local and does not require browser globals or remote
JavaScript imports. Use a DNS hostname, a canonical IPv4 address, or an IPv6
literal; credentials, query strings, fragments, and non-root paths are rejected.

```bash
export ASSETPULSE_BASE_URL="https://example.onrender.com"
export ASSETPULSE_K6_PASSWORD="<demo-operations-admin-password>"
export ASSETPULSE_K6_ENVIRONMENT="render-free"
export ASSETPULSE_K6_DEPLOY_REVISION="<deployed-git-revision>"
k6 run infra/performance/telemetry-to-alert.k6.js
```

The email defaults to `admin@northstar.example`. Override it with
`ASSETPULSE_K6_EMAIL` when necessary. HTTPS is mandatory for non-loopback targets.
For a local-only run, set `ASSETPULSE_K6_ALLOW_HTTP=true` and use `localhost`,
`127.0.0.1`, or `::1`.

Before each submission the script snapshots matching alert state. Success requires
an exact three-occurrence increase and the exact latest timestamp submitted by that
iteration. The single-VU limit prevents this script's own iterations from overlapping;
unrelated concurrent demo activity makes the evidence fail closed instead of passing
on a pre-existing alert. The small default is 3 iterations. The supported knobs are:

| Environment variable                    | Default | Hard maximum |
| --------------------------------------- | ------: | -----------: |
| `ASSETPULSE_K6_VUS`                     |       1 |            1 |
| `ASSETPULSE_K6_ITERATIONS`              |       3 |           20 |
| `ASSETPULSE_K6_REQUEST_TIMEOUT_SECONDS` |      10 |           30 |
| `ASSETPULSE_K6_ALERT_WAIT_SECONDS`      |      30 |           60 |

`ASSETPULSE_K6_RUN_ID` can supply a short, non-secret evidence identifier. The
machine-readable report defaults to `k6-telemetry-to-alert-summary.json`; set a safe
relative `ASSETPULSE_K6_SUMMARY_PATH` to change it. The parent directory must already
exist.

## Evidence and interpretation

The iteration threshold requires every requested iteration to finish, so a run
interrupted by the three-minute deadline cannot pass on a partial sample.

The JSON report records the target origin, environment label, deployment revision,
bounded configuration, k6 state, thresholds, HTTP failure rate, request duration,
telemetry acceptance latency, and telemetry-to-alert latency. It intentionally omits
the email, password, CSRF token, session cookie, request payloads, and response
bodies.

Run the public readiness/smoke verifier first so free-tier wake-up time is measured
separately. Retain the exact k6 command (with the password redacted), the JSON report,
the deployed revision, region/runtime tier, and whether the service was already
awake. Run against quiet demo state: newer matching state or concurrent occurrences
invalidate the causal check. Do not interpret this bounded scenario as a throughput
limit or service-level objective.
