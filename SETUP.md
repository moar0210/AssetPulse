# Run AssetPulse locally

The local stack runs PostgreSQL, the Spring Boot API, and the React client in
Docker. Start with Git, a running Docker Engine or Docker Desktop using Linux
containers, and Docker Compose v2. The first build downloads the pinned images
and dependencies. Java and Node are only needed on your machine for the optional
checks and command-line simulator below.

## Clone and prepare the environment

```text
git clone https://github.com/moar0210/AssetPulse.git
cd AssetPulse
docker version
docker compose version
```

Choose one setup block. Both create an ignored `.env` with different random
database passwords and stop if that file already exists. Keep an existing file
when returning to a database you have already started: editing its passwords does
not change the credentials stored in the database volume.

PowerShell 7:

```powershell
if (Test-Path -LiteralPath .env) { throw ".env already exists; keep your current settings." }
$localEnvironment = Get-Content -LiteralPath .env.example -Raw
$localEnvironment = $localEnvironment.Replace('replace-with-local-app-password', [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
$localEnvironment = $localEnvironment.Replace('replace-with-local-admin-password', [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)))
New-Item -Path .env -ItemType File -Value $localEnvironment -ErrorAction Stop | Out-Null
Remove-Variable localEnvironment
```

Bash, with OpenSSL available:

```bash
(
  set -eu
  umask 077
  set -C
  local_app_password=$(openssl rand -hex 32)
  local_admin_password=$(openssl rand -hex 32)
  sed \
    -e "s/replace-with-local-app-password/$local_app_password/" \
    -e "s/replace-with-local-admin-password/$local_admin_password/" \
    .env.example > .env
)
```

Run the remaining commands from the repository root unless a block changes
directory. Compose reads `.env` automatically. Variables already exported in
your terminal take precedence, so use a fresh terminal if another configuration
is active. Do not commit `.env` or use real equipment data in this demo.

## Start and sign in

```text
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
docker compose ps
```

All three services should become healthy. Open [the local app](http://localhost:8080).
The first start applies Flyway migrations and seeds two organisations, four
accounts, three assets, and their sensors and temperature rules.

Every demo account uses **`AssetPulse1!`**. These are application credentials;
the database passwords created above are separate.

| Email                          | Organisation            | Role             |
| ------------------------------ | ----------------------- | ---------------- |
| `admin@northstar.example`      | Northstar Operations    | Operations Admin |
| `technician@northstar.example` | Northstar Operations    | Technician       |
| `viewer@northstar.example`     | Northstar Operations    | Viewer           |
| `admin@riverside.example`      | Riverside Manufacturing | Operations Admin |

Sign in as the Northstar admin. On Dashboard, **Launch and open alerts** sends
the Overheating Pump scenario through the API. Follow the live alert, acknowledge
it, create a work order, and assign Theo Technician. Sign out and use the
technician account to start and complete the assigned work. The Viewer can read
the product but cannot mutate it; Riverside has separate tenant data.

[API status](http://localhost:8080/api/v1/status) returns
`{"status":"available"}` when the HTTP process is available. A successful login
and asset read also verify the database path. The
[OpenAPI contract](http://localhost:8080/openapi.json) describes the implemented API.

If startup fails, inspect `docker compose ps` and
`docker compose logs --tail 100`. A port conflict can be resolved by changing
`ASSETPULSE_HTTP_PORT` in `.env` and using that port in the browser. PostgreSQL
has no published host port; the example datasource URL is overridden by Compose
with its internal database address. Running Spring directly requires a separately
reachable PostgreSQL database and explicitly exported datasource settings.

## Run both telemetry scenarios

Install Node 24 to run the command-line simulator against the healthy stack:

```text
cd simulator
npm ci
npm run normal
npm run overheating
cd ..
```

These commands use the local origin and seeded Northstar admin. They submit
bounded batches through the API. See [simulator usage](simulator/USAGE.md) for
custom origins, credentials, and fixed observation times for exact retries.
If you change the local port, also set `ASSETPULSE_BASE_URL` for the simulator.

## Reset active work or stop the stack

As Operations Admin, open Dashboard, choose **Prepare reset** under
**Reset active demo state**, then **Confirm reset**. The reset completes active
work and resolves active alerts through legal transitions, with a limit of 100
active alerts and 100 active work orders. It preserves telemetry, completed work,
history, audit, and alert cooldowns. Repeating a scenario during its five-minute
cooldown can therefore produce no new alert.

To shut down and later resume using the same database:

```text
docker compose down
docker compose up --wait --wait-timeout 180
```

The named PostgreSQL volume survives these commands. Sign in again after an API
restart because sessions are process-local. To rebuild after pulling changes,
use the earlier `up --build --wait` command. Volume deletion is a separate,
destructive database reset and is not needed for ordinary setup or shutdown.

## Run the checks

Use **Java 21** with `JAVA_HOME` set and **Node 24**. The Maven Wrapper downloads
the pinned Maven version; a separate Maven install is unnecessary. Docker must
be running for the PostgreSQL/Testcontainers backend tests.

From `backend`, run `./mvnw clean verify` in Bash or
`.\mvnw.cmd clean verify` in PowerShell, then return to the repository root.

Frontend and simulator checks:

```text
cd frontend
npm ci
npm run format:check
npm run lint
npm run typecheck
npm test
npm run build
cd ../simulator
npm ci
npm run check
npm test
cd ..
```

Contract, smoke-helper, and performance-helper tests do not need a running app:

```text
node --test infra/smoke/openapi-contract.test.mjs infra/smoke/public-smoke.test.mjs infra/performance/alert-evidence.test.mjs infra/performance/origin.test.mjs
```

For the three browser journeys, start the Compose stack, then:

```text
cd frontend
npm ci
npx playwright install chromium
npm run test:e2e
cd ..
```

On Linux, `npx playwright install --with-deps chromium` also installs Chromium's
system libraries and may require administrator access. The journeys cover live
incident completion, role/tenant denial, and two-client conflict recovery. They
write synthetic telemetry and reset active demo state; use your local stack for
routine runs. Reports are written under `frontend/playwright-report/`, with
failure traces, screenshots, and videos under `frontend/test-results/`.

Playwright defaults to `http://127.0.0.1:8080`. For a changed port, set
`$env:ASSETPULSE_BASE_URL = 'http://127.0.0.1:8081'` in PowerShell or
`export ASSETPULSE_BASE_URL=http://127.0.0.1:8081` in Bash before the run.

The separate public smoke command in the [operations runbook](infra/operations/RUNBOOK.md)
checks Secure session cookies even with `--allow-http`. The default local `.env`
uses `ASSETPULSE_SESSION_COOKIE_SECURE=false` for HTTP; CI explicitly enables it
for that stricter smoke check. Public HTTPS hosting requires Secure cookies and
follows the [hosting guide](HOSTING.md).
