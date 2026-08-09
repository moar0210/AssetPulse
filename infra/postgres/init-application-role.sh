#!/bin/sh
set -eu

: "${ASSETPULSE_APP_USERNAME:?ASSETPULSE_APP_USERNAME is required}"
: "${ASSETPULSE_APP_PASSWORD:?ASSETPULSE_APP_PASSWORD is required}"

psql \
  --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=app_username="$ASSETPULSE_APP_USERNAME" \
  --set=app_password="$ASSETPULSE_APP_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_username', :'app_password') \gexec
SELECT format(
  'GRANT CONNECT, TEMPORARY ON DATABASE %I TO %I',
  current_database(),
  :'app_username'
) \gexec
SELECT format(
  'GRANT USAGE, CREATE ON SCHEMA public TO %I',
  :'app_username'
) \gexec
SQL
