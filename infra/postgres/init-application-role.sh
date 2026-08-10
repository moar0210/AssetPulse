#!/bin/sh
set -eu

: "${ASSETPULSE_APP_USERNAME:?ASSETPULSE_APP_USERNAME is required}"
: "${ASSETPULSE_APP_PASSWORD:?ASSETPULSE_APP_PASSWORD is required}"

PGUSER="${PGUSER:-${POSTGRES_USER:-}}"
PGDATABASE="${PGDATABASE:-${POSTGRES_DB:-$PGUSER}}"
PGPASSWORD="${PGPASSWORD:-${POSTGRES_PASSWORD:-}}"
PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-5}"
ASSETPULSE_DB_READY_ATTEMPTS="${ASSETPULSE_DB_READY_ATTEMPTS:-30}"
ASSETPULSE_DB_READY_INTERVAL_SECONDS="${ASSETPULSE_DB_READY_INTERVAL_SECONDS:-2}"

: "${PGUSER:?PGUSER or POSTGRES_USER is required}"
: "${PGDATABASE:?PGDATABASE or POSTGRES_DB is required}"

case "$PGCONNECT_TIMEOUT" in
  ''|*[!0-9]*)
    echo "PGCONNECT_TIMEOUT must be a positive integer" >&2
    exit 2
    ;;
esac

if ! [ "$PGCONNECT_TIMEOUT" -gt 0 ] 2>/dev/null; then
  echo "PGCONNECT_TIMEOUT must be a positive integer" >&2
  exit 2
fi

case "$ASSETPULSE_DB_READY_ATTEMPTS" in
  ''|*[!0-9]*)
    echo "ASSETPULSE_DB_READY_ATTEMPTS must be a positive integer" >&2
    exit 2
    ;;
esac

if ! [ "$ASSETPULSE_DB_READY_ATTEMPTS" -gt 0 ] 2>/dev/null; then
  echo "ASSETPULSE_DB_READY_ATTEMPTS must be a positive integer" >&2
  exit 2
fi

case "$ASSETPULSE_DB_READY_INTERVAL_SECONDS" in
  ''|*[!0-9]*)
    echo "ASSETPULSE_DB_READY_INTERVAL_SECONDS must be a non-negative integer" >&2
    exit 2
    ;;
esac

if ! [ "$ASSETPULSE_DB_READY_INTERVAL_SECONDS" -ge 0 ] 2>/dev/null; then
  echo "ASSETPULSE_DB_READY_INTERVAL_SECONDS must be a non-negative integer" >&2
  exit 2
fi

export ASSETPULSE_APP_USERNAME ASSETPULSE_APP_PASSWORD
export PGUSER PGDATABASE PGPASSWORD PGCONNECT_TIMEOUT

attempt=1
until pg_isready --quiet --timeout="$PGCONNECT_TIMEOUT"; do
  if [ "$attempt" -ge "$ASSETPULSE_DB_READY_ATTEMPTS" ]; then
    echo "PostgreSQL did not become ready after $ASSETPULSE_DB_READY_ATTEMPTS attempts" >&2
    exit 1
  fi

  echo "Waiting for PostgreSQL ($attempt/$ASSETPULSE_DB_READY_ATTEMPTS)" >&2
  sleep "$ASSETPULSE_DB_READY_INTERVAL_SECONDS"
  attempt=$((attempt + 1))
done

bootstrap_database_user="$(
  psql \
    --no-password \
    --no-psqlrc \
    --tuples-only \
    --no-align \
    --command='SELECT current_user'
)"

if [ "$ASSETPULSE_APP_USERNAME" = "$bootstrap_database_user" ]; then
  echo "Application role must differ from the bootstrap administrator" >&2
  exit 3
fi

psql \
  --no-password \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_username ASSETPULSE_APP_USERNAME

BEGIN;

SELECT EXISTS (
  SELECT 1
  FROM pg_catalog.pg_roles
  WHERE rolname = :'app_username'
) AS app_role_exists
\gset

\if :app_role_exists
\else
  CREATE ROLE :"app_username"
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1;
\endif

SELECT
  app_role.rolsuper
    OR app_role.rolreplication
    OR app_role.rolbypassrls AS app_role_has_restricted_attributes,
  bootstrap_role.rolsuper AS bootstrap_role_is_superuser
FROM pg_catalog.pg_roles AS app_role
JOIN pg_catalog.pg_roles AS bootstrap_role
  ON bootstrap_role.rolname = current_user
WHERE app_role.rolname = :'app_username'
\gset

\if :app_role_has_restricted_attributes
  \if :bootstrap_role_is_superuser
    ALTER ROLE :"app_username" WITH NOSUPERUSER NOREPLICATION NOBYPASSRLS;
  \else
    DO $do$
    BEGIN
      RAISE EXCEPTION 'Application role has restricted attributes that this administrator cannot remove';
    END;
    $do$;
  \endif
\endif

ALTER ROLE :"app_username"
  WITH NOCREATEDB NOCREATEROLE NOINHERIT CONNECTION LIMIT -1;

SELECT
  NOT rolsuper
  AND NOT rolcreatedb
  AND NOT rolcreaterole
  AND NOT rolinherit
  AND NOT rolreplication
  AND NOT rolbypassrls AS app_role_has_safe_attributes
FROM pg_catalog.pg_roles
WHERE rolname = :'app_username'
\gset

\if :app_role_has_safe_attributes
\else
  DO $do$
  BEGIN
    RAISE EXCEPTION 'Application role has privileged attributes that this administrator cannot remove';
  END;
  $do$;
\endif

SELECT current_database() AS app_database
\gset

GRANT CONNECT, TEMPORARY ON DATABASE :"app_database" TO :"app_username";
GRANT USAGE, CREATE ON SCHEMA public TO :"app_username";

COMMIT;
SQL

printf '%s\n%s\n' "$ASSETPULSE_APP_PASSWORD" "$ASSETPULSE_APP_PASSWORD" \
  | psql \
    --no-password \
    --no-psqlrc \
    --set=ON_ERROR_STOP=1 \
    --set=app_username="$ASSETPULSE_APP_USERNAME" \
    --command='\password :"app_username"'

psql \
  --no-password \
  --no-psqlrc \
  --set=ON_ERROR_STOP=1 <<'SQL'
\getenv app_username ASSETPULSE_APP_USERNAME

ALTER ROLE :"app_username" WITH LOGIN;

SELECT
  rolcanlogin
  AND NOT rolsuper
  AND NOT rolcreatedb
  AND NOT rolcreaterole
  AND NOT rolinherit
  AND NOT rolreplication
  AND NOT rolbypassrls AS app_role_has_safe_attributes
FROM pg_catalog.pg_roles
WHERE rolname = :'app_username'
\gset

\if :app_role_has_safe_attributes
\else
  DO $do$
  BEGIN
    RAISE EXCEPTION 'Application role bootstrap did not produce the required safe attributes';
  END;
  $do$;
\endif
SQL
