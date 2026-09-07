#!/bin/bash
set -euo pipefail

fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/assetpulse-bootstrap.XXXXXX")"
cleanup() {
  rm -f -- "$fixture_dir/psql" "$fixture_dir/pg_isready" "$fixture_dir/ca.crt" \
    "$fixture_dir/calls" "$fixture_dir/output"
  rmdir -- "$fixture_dir"
}
trap cleanup EXIT

cat > "$fixture_dir/pg_isready" <<'SH'
#!/bin/sh
exit 0
SH
cat > "$fixture_dir/psql" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >> "$BOOTSTRAP_CALLS"
case "$*" in
  *'SELECT current_user'*) printf '%s\n' postgres ;;
  *'\conninfo'*)
    if [ "${BOOTSTRAP_TLS:-t}" = t ]; then
      printf '%s\n' 'SSL connection (protocol: TLSv1.3, cipher: TLS_AES_256_GCM_SHA384)'
    fi
    ;;
  *'\password'*)
    IFS= read -r first
    IFS= read -r second
    test "$first" = "$ASSETPULSE_APP_PASSWORD"
    test "$second" = "$ASSETPULSE_APP_PASSWORD"
    ;;
  *)
    input="$(cat)"
    case "$input" in
      *'PASSWORD :'*)
        if [ "${BOOTSTRAP_PASSWORD_FAIL:-false}" = true ]; then
          printf 'Rejected sensitive statement: %s\n' "$ASSETPULSE_APP_PASSWORD"
          printf 'Sensitive context: %s\n' "$ASSETPULSE_APP_PASSWORD" >&2
          exit 1
        fi
        ;;
    esac
    ;;
esac
SH
chmod +x "$fixture_dir/psql" "$fixture_dir/pg_isready"
touch "$fixture_dir/ca.crt"
export PATH="$fixture_dir:$PATH"
export BOOTSTRAP_CALLS="$fixture_dir/calls"
export PGHOST=database-tls PGDATABASE=assetpulse PGUSER=postgres
export PGSSLMODE=verify-full PGCHANNELBINDING=require PGSSLROOTCERT="$fixture_dir/ca.crt"
export ASSETPULSE_APP_USERNAME=assetpulse
export ASSETPULSE_APP_PASSWORD="ci-only-sensitive-marker'\\ spaced"
unset PGSERVICE PGSERVICEFILE

run_bootstrap() {
  sh infra/postgres/init-application-role.sh > "$fixture_dir/output" 2>&1
}
reject_before_connection() {
  : > "$fixture_dir/calls"
  if run_bootstrap; then
    echo 'Unsafe bootstrap configuration was accepted' >&2
    exit 1
  fi
  test ! -s "$fixture_dir/calls"
}

export ASSETPULSE_PASSWORD_MODE=unsupported
reject_before_connection
export ASSETPULSE_PASSWORD_MODE=server-hashed
export PGSSLMODE=require
reject_before_connection
export PGSSLMODE=verify-full PGCHANNELBINDING=prefer
reject_before_connection
export PGCHANNELBINDING=require PGSSLROOTCERT="$fixture_dir/missing.crt"
reject_before_connection
export PGSSLROOTCERT="$fixture_dir/ca.crt" PGSERVICE=untrusted-override
reject_before_connection
unset PGSERVICE
export PGHOST='/tmp,database-tls'
reject_before_connection
export PGHOST=database-tls
export PGDATABASE='dbname=assetpulse sslmode=disable'
reject_before_connection
export PGDATABASE=assetpulse

export BOOTSTRAP_TLS=f
if run_bootstrap; then
  echo 'Non-TLS connection was accepted' >&2
  exit 1
fi
grep -F 'require an active TLS connection' "$fixture_dir/output" >/dev/null
test "$(wc -l < "$fixture_dir/calls")" -eq 2
export BOOTSTRAP_TLS=t BOOTSTRAP_PASSWORD_FAIL=true
if run_bootstrap; then
  echo 'Failed password update was accepted' >&2
  exit 1
fi
grep -F 'Application password could not be set over verified TLS' "$fixture_dir/output" >/dev/null
if grep -F -- "$ASSETPULSE_APP_PASSWORD" "$fixture_dir/output" "$fixture_dir/calls"; then
  echo 'Bootstrap exposed password material' >&2
  exit 1
fi
unset BOOTSTRAP_PASSWORD_FAIL
run_bootstrap
export ASSETPULSE_PASSWORD_MODE=client-hashed
run_bootstrap
grep -F '\password' "$fixture_dir/calls" >/dev/null
echo 'Bootstrap transport guards, password failure redaction, and both modes passed.'
