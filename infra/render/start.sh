#!/bin/bash

set -eu

: "${SPRING_DATASOURCE_URL:?SPRING_DATASOURCE_URL is required}"
: "${SPRING_DATASOURCE_USERNAME:?SPRING_DATASOURCE_USERNAME is required}"
: "${SPRING_DATASOURCE_PASSWORD:?SPRING_DATASOURCE_PASSWORD is required}"

frontend_port="${PORT:-${FRONTEND_PORT:-8080}}"
case "$frontend_port" in
  '' | *[!0-9]*)
    echo "PORT must be an integer between 1 and 65535" >&2
    exit 64
    ;;
esac
if [ "$frontend_port" -lt 1 ] || [ "$frontend_port" -gt 65535 ]; then
  echo "PORT must be an integer between 1 and 65535" >&2
  exit 64
fi

export FRONTEND_PORT="$frontend_port"
export BACKEND_UPSTREAM=127.0.0.1:8081
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT=8081

envsubst '${FRONTEND_PORT} ${BACKEND_UPSTREAM}' \
  < /etc/nginx/templates/nginx.conf.template \
  > /tmp/nginx.conf
nginx -t -c /tmp/nginx.conf

backend_pid=''
frontend_pid=''

stop_processes() {
  trap - INT TERM
  if [ -n "$frontend_pid" ]; then
    kill -TERM "$frontend_pid" 2>/dev/null || true
  fi
  if [ -n "$backend_pid" ]; then
    kill -TERM "$backend_pid" 2>/dev/null || true
  fi
  wait "$frontend_pid" 2>/dev/null || true
  wait "$backend_pid" 2>/dev/null || true
}

trap 'stop_processes; exit 130' INT
trap 'stop_processes; exit 143' TERM

java -jar /app/app.jar &
backend_pid=$!

nginx -c /tmp/nginx.conf -g 'daemon off;' &
frontend_pid=$!

set +e
wait -n "$backend_pid" "$frontend_pid"
process_status=$?
set -e

stop_processes
exit "$process_status"
