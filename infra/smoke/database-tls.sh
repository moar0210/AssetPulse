#!/bin/bash
set -euo pipefail

web_image="${ASSETPULSE_WEB_IMAGE:-assetpulse-web:local}"
bootstrap_image="${ASSETPULSE_BOOTSTRAP_IMAGE:-assetpulse-database-bootstrap:local}"
fixture_name="assetpulse-database-tls-$$"
fixture_dir="$(mktemp -d "${TMPDIR:-/tmp}/assetpulse-database-tls.XXXXXX")"
bundle_path=/etc/ssl/certs/ca-certificates.crt
fixture_password="ci-only-tls-password'\\ spaced"

cleanup() {
  docker rm --force "$fixture_name" >/dev/null 2>&1 || true
  docker network rm "$fixture_name" >/dev/null 2>&1 || true
  rm -rf -- "$fixture_dir"
}
trap cleanup EXIT

mkdir "$fixture_dir/probe"
chmod 755 "$fixture_dir" "$fixture_dir/probe"
for client_image in "$web_image" "$bootstrap_image"; do
  docker run --rm --entrypoint sh "$client_image" \
    -c 'test -r /etc/ssl/certs/ca-certificates.crt && test -s /etc/ssl/certs/ca-certificates.crt'
done

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 \
  -subj /CN=AssetPulse-test-CA \
  -keyout "$fixture_dir/ca.key" -out "$fixture_dir/ca.crt" 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 1 \
  -subj /CN=AssetPulse-untrusted-test-CA \
  -keyout "$fixture_dir/untrusted.key" -out "$fixture_dir/untrusted.crt" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -subj /CN=database-tls \
  -keyout "$fixture_dir/server.key" -out "$fixture_dir/server.csr" 2>/dev/null
printf '%s\n' 'subjectAltName=DNS:database-tls' 'extendedKeyUsage=serverAuth' \
  > "$fixture_dir/server.ext"
openssl x509 -req -sha256 -days 1 \
  -in "$fixture_dir/server.csr" \
  -CA "$fixture_dir/ca.crt" -CAkey "$fixture_dir/ca.key" -CAcreateserial \
  -extfile "$fixture_dir/server.ext" -out "$fixture_dir/server.crt" 2>/dev/null
chmod 644 "$fixture_dir/ca.crt" "$fixture_dir/untrusted.crt" "$fixture_dir/server.crt"
chmod 600 "$fixture_dir/server.key"

docker run --rm \
  --mount "type=bind,src=$PWD/infra/smoke/DatabaseTlsProbe.java,dst=/src/DatabaseTlsProbe.java,readonly" \
  --mount "type=bind,src=$fixture_dir/probe,dst=/probe" \
  --entrypoint javac eclipse-temurin:21.0.11_10-jdk-alpine-3.23 \
  --release 21 -d /probe /src/DatabaseTlsProbe.java

docker network create --internal "$fixture_name" >/dev/null
docker run --detach --name "$fixture_name" \
  --network "$fixture_name" --network-alias database-tls --network-alias wrong-database-tls \
  --mount "type=bind,src=$fixture_dir/server.crt,dst=/certificates/server.crt,readonly" \
  --mount "type=bind,src=$fixture_dir/server.key,dst=/certificates/server.key,readonly" \
  --tmpfs /var/lib/postgresql/data:rw,nosuid,nodev \
  --env POSTGRES_DB=assetpulse_tls --env POSTGRES_PASSWORD="$fixture_password" \
  --env POSTGRES_HOST_AUTH_METHOD=scram-sha-256 \
  --entrypoint sh postgres:17.10-alpine3.23 -c \
  'cp /certificates/server.crt /tmp/server.crt && cp /certificates/server.key /tmp/server.key && chown postgres:postgres /tmp/server.* && chmod 600 /tmp/server.key && exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/tmp/server.crt -c ssl_key_file=/tmp/server.key' \
  >/dev/null

database_ready=false
for attempt in {1..30}; do
  if docker exec "$fixture_name" pg_isready --quiet --host=127.0.0.1 --username=postgres; then
    database_ready=true
    break
  fi
  sleep 1
done
if [ "$database_ready" != true ]; then
  docker logs "$fixture_name"
  exit 1
fi

for attempt in 1 2; do
  docker run --rm --network "$fixture_name" \
    --mount "type=bind,src=$fixture_dir/ca.crt,dst=$bundle_path,readonly" \
    --env PGHOST=database-tls --env PGDATABASE=assetpulse_tls --env PGUSER=postgres \
    --env PGPASSWORD="$fixture_password" --env PGSSLMODE=verify-full --env PGCHANNELBINDING=require \
    --env ASSETPULSE_PASSWORD_MODE=server-hashed \
    --env ASSETPULSE_APP_USERNAME=assetpulse_tls --env ASSETPULSE_APP_PASSWORD="$fixture_password" \
    "$bootstrap_image"
done

psql_check() {
  docker run --rm --network "$fixture_name" --entrypoint psql \
    --mount "type=bind,src=$fixture_dir/$2,dst=$bundle_path,readonly" \
    --env PGHOST="$1" --env PGDATABASE=assetpulse_tls --env PGUSER=assetpulse_tls \
    --env PGPASSWORD="$fixture_password" --env PGSSLMODE=verify-full --env PGCHANNELBINDING=require \
    --env PGCONNECT_TIMEOUT=5 \
    "$bootstrap_image" --no-password --no-psqlrc --tuples-only --no-align \
    --set=ON_ERROR_STOP=1 --command='SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()'
}

test "$(psql_check database-tls ca.crt)" = t
if psql_check wrong-database-tls ca.crt > "$fixture_dir/psql-wrong-host.log" 2>&1; then
  echo "libpq accepted a mismatched database host name" >&2
  exit 1
fi
grep -F 'does not match host name' "$fixture_dir/psql-wrong-host.log"
if psql_check database-tls untrusted.crt > "$fixture_dir/psql-untrusted.log" 2>&1; then
  echo "libpq accepted an untrusted database certificate" >&2
  exit 1
fi
grep -F 'certificate verify failed' "$fixture_dir/psql-untrusted.log"

jdbc_check() {
  docker run --rm --network "$fixture_name" --read-only --tmpfs /tmp \
    --mount "type=bind,src=$fixture_dir/$2,dst=$bundle_path,readonly" \
    --mount "type=bind,src=$fixture_dir/probe,dst=/probe,readonly" \
    --env ASSETPULSE_TLS_PASSWORD="$fixture_password" \
    --entrypoint java "$web_image" \
    -Dloader.path=/probe -Dloader.main=DatabaseTlsProbe \
    -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$1" "$3"
}

jdbc_check database-tls ca.crt trusted
jdbc_check wrong-database-tls ca.crt wrong-host
jdbc_check database-tls untrusted.crt untrusted
echo 'Both database clients passed verified TLS and rejected an untrusted CA and mismatched host name.'
