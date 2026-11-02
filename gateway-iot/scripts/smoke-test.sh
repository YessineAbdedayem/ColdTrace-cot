#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

failures=0
subscriber_pid=""
output_file="$(mktemp)"
cleanup() {
  if [[ -n "$subscriber_pid" ]]; then
    kill -INT "$subscriber_pid" 2>/dev/null || true
    wait "$subscriber_pid" 2>/dev/null || true
  fi
  rm -f "$output_file"
}
trap cleanup EXIT

check_result() {
  local name="$1"
  shift
  if "$@"; then
    printf 'PASS: %s\n' "$name"
  else
    printf 'FAIL: %s\n' "$name"
    failures=$((failures + 1))
  fi
}

services_running() {
  local services service
  services="$(docker compose ps --services --filter status=running)" || return 1
  for service in site-broker cloud-broker node-red simulator mongo; do
    if ! grep -Fxq "$service" <<< "$services"; then
      printf '  Service is not running: %s\n' "$service"
      return 1
    fi
  done
}

check_telemetry() {
  local attempt found topic
  local -a expected=(
    coldtrace/site01/S01/telemetry
    coldtrace/site01/S02/telemetry
    coldtrace/site01/S03/telemetry
    coldtrace/site01/S04/telemetry
    coldtrace/site02/S05/telemetry
    coldtrace/site02/S06/telemetry
    coldtrace/site02/S07/telemetry
    coldtrace/site03/S08/telemetry
    coldtrace/site03/S09/telemetry
    coldtrace/site03/S10/telemetry
  )

  : > "$output_file"
  docker compose exec -T cloud-broker sh -c \
    'exec mosquitto_sub -h 127.0.0.1 -p 8883 --cafile /mosquitto/certs/ca.crt -u "$BACKEND_USER" -P "$BACKEND_PASS" -t "coldtrace/+/+/telemetry" -v -W 30' \
    > "$output_file" 2>&1 &
  subscriber_pid=$!

  attempt=0
  while (( attempt < 30 )); do
    found=0
    for topic in "${expected[@]}"; do
      if grep -Fq "$topic " "$output_file"; then
        found=$((found + 1))
      fi
    done
    if (( found == ${#expected[@]} )); then
      kill -INT "$subscriber_pid" 2>/dev/null || true
      wait "$subscriber_pid" 2>/dev/null || true
      subscriber_pid=""
      return 0
    fi
    if ! kill -0 "$subscriber_pid" 2>/dev/null; then
      break
    fi
    sleep 1
    attempt=$((attempt + 1))
  done

  if [[ -n "$subscriber_pid" ]]; then
    kill -INT "$subscriber_pid" 2>/dev/null || true
    wait "$subscriber_pid" 2>/dev/null || true
    subscriber_pid=""
  fi
  for topic in "${expected[@]}"; do
    if ! grep -Fq "$topic " "$output_file"; then
      printf '  Missing telemetry topic: %s\n' "$topic"
    fi
  done
  if [[ -s "$output_file" ]]; then
    printf '  Subscriber output:\n'
    tail -n 10 "$output_file"
  fi
  return 1
}

check_mongo() {
  local attempt mongo_output
  for attempt in {1..30}; do
    if mongo_output="$(docker compose exec -T mongo sh -c '
      set -e
      mongosh --quiet \
        --username "$MONGO_INITDB_ROOT_USERNAME" \
        --password "$MONGO_INITDB_ROOT_PASSWORD" \
        --authenticationDatabase admin \
        --eval "if (db.adminCommand({ping: 1}).ok !== 1) quit(1)"
      mongosh --quiet \
        --username "$MONGO_INITDB_ROOT_USERNAME" \
        --password "$MONGO_INITDB_ROOT_PASSWORD" \
        --authenticationDatabase admin \
        --eval "
          const database = db.getSiblingDB(process.env.MONGO_DB || \"coldtrace\");
          const indexes = database.measurements.getIndexes();
          if (!indexes.some(index => index.name === \"msgId_unique\" && index.unique && index.key.msgId === 1)) quit(1);
          print(\"MongoDB ping and unique msgId index verified.\");
        "
    ' 2>&1)"; then
      printf '%s\n' "$mongo_output"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' "$mongo_output"
  return 1
}

check_result "all five Compose services are running" services_running
check_result "telemetry arrives from all ten sensors on all three sites" check_telemetry
check_result "MongoDB ping and unique msgId index" check_mongo

if (( failures > 0 )); then
  printf 'Smoke test failed: %s check(s) failed.\n' "$failures"
  exit 1
fi
printf 'All smoke checks passed.\n'
