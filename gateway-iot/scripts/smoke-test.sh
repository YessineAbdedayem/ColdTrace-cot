#!/usr/bin/env bash
# End-to-end checks of the running stack. It takes about four minutes, and it
# briefly stops the cloud broker and clears any active simulator faults.
set -euo pipefail

cd "$(dirname "$0")/.."
# Stops Git Bash on Windows from rewriting container paths.
export MSYS_NO_PATHCONV=1

OUTAGE_SECONDS=25
MIN_REPLAYED=25

failures=0
subscriber_pid=""
broker_stopped=""
output_file="$(mktemp)"
cleanup() {
  if [[ -n "$subscriber_pid" ]]; then
    kill -INT "$subscriber_pid" 2>/dev/null || true
    wait "$subscriber_pid" 2>/dev/null || true
  fi
  if [[ -n "$broker_stopped" ]]; then
    docker compose start cloud-broker >/dev/null 2>&1 || true
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

# wait_until <seconds> <command...>: retries the command every 2 seconds.
wait_until() {
  local deadline=$((SECONDS + $1))
  shift
  until "$@"; do
    if (( SECONDS >= deadline )); then
      return 1
    fi
    sleep 2
  done
}

mongo_eval() {
  docker compose exec -T mongo sh -c '
    mongosh --quiet \
      --username "$MONGO_INITDB_ROOT_USERNAME" \
      --password "$MONGO_INITDB_ROOT_PASSWORD" \
      --authenticationDatabase admin \
      "${MONGO_DB:-coldtrace}" --eval "$1"' _ "$1" | tr -d '\r'
}

publish_as_gateway() {
  docker compose exec -T cloud-broker sh -c \
    'exec mosquitto_pub -h 127.0.0.1 -p 8883 --cafile /mosquitto/certs/ca.crt -u "$GW_USER" -P "$GW_PASS" -q 1 -t "$1" -m "$2"' \
    _ "$1" "$2"
}

services_running() {
  local services service
  services="$(docker compose ps --services --filter status=running | tr -d '\r')" || return 1
  for service in site-broker cloud-broker node-red simulator mongo api; do
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

api_healthy() {
  local address
  address="$(docker compose port api 8080 | tr -d '\r')" || return 1
  [[ "$(curl -fsS -m 5 "http://$address/api/health" 2>/dev/null)" == '{"status":"ok"}' ]]
}

check_api() {
  wait_until 180 api_healthy || { printf '  /api/health did not answer in 180 s\n'; return 1; }
}

all_sensors_stored_since() {
  [[ "$(mongo_eval "print(db.measurements.distinct('sensorId', {receivedAt: {\$gte: ISODate('$1')}}).length)")" == "10" ]]
}

check_storage() {
  local since
  since="$(mongo_eval "print(new Date().toISOString())")"
  wait_until 60 all_sensors_stored_since "$since" || { printf '  Not all ten sensors reached MongoDB\n'; return 1; }
}

gateway_online() {
  [[ "$(mongo_eval "print(db.gateways.countDocuments({_id: 'site01', status: 'online'}))")" == "1" ]]
}

check_gateway_status() {
  wait_until 90 gateway_online || { printf '  site01 gateway is not online in MongoDB\n'; return 1; }
}

count_msg() {
  mongo_eval "print(db.measurements.countDocuments({msgId: '$1'}))"
}

msg_stored() {
  [[ "$(count_msg "$1")" == "1" ]]
}

check_duplicates_and_rejects() {
  local run now duplicate bad good_count bad_count
  run="smoke-$(date +%s)"
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  duplicate="{\"v\":1,\"msgId\":\"$run-dup\",\"siteId\":\"site01\",\"sensorId\":\"S01\",\"ts\":\"$now\",\"humidity\":60}"
  bad="{\"v\":2,\"msgId\":\"$run-bad\",\"siteId\":\"site01\",\"sensorId\":\"S01\",\"ts\":\"$now\",\"humidity\":60}"

  publish_as_gateway coldtrace/site01/S01/telemetry "$duplicate" || return 1
  publish_as_gateway coldtrace/site01/S01/telemetry "$duplicate" || return 1
  publish_as_gateway coldtrace/site01/S01/telemetry "$bad" || return 1

  wait_until 15 msg_stored "$run-dup" || { printf '  The test reading was not stored\n'; return 1; }
  sleep 3
  good_count="$(count_msg "$run-dup")"
  bad_count="$(count_msg "$run-bad")"
  mongo_eval "db.measurements.deleteMany({msgId: /^$run/})" >/dev/null
  if [[ "$good_count" != "1" || "$bad_count" != "0" ]]; then
    printf '  Expected duplicate=1 and rejected=0, got duplicate=%s and rejected=%s\n' "$good_count" "$bad_count"
    return 1
  fi
}

check_alerts() {
  local since result=0
  since="$(mongo_eval "print(new Date().toISOString())")"
  mongo_eval "db.incidents.deleteMany({sensorId: 'S02', status: 'open'})" >/dev/null
  docker compose exec -T simulator node alert-e2e.js || result=1
  # Test incidents should not count in KPIs later.
  mongo_eval "db.incidents.deleteMany({sensorId: 'S02', openedAt: {\$gte: ISODate('$since')}})" >/dev/null
  return "$result"
}

replay_complete() {
  local filter="receivedAt: {\$gte: ISODate('$1'), \$lte: ISODate('$2')}, replayed: true"
  [[ "$(mongo_eval "print(db.measurements.distinct('sensorId', {$filter}).length)")" == "10" ]] || return 1
  (( "$(mongo_eval "print(db.measurements.countDocuments({$filter}))")" >= MIN_REPLAYED ))
}

live_after() {
  (( "$(mongo_eval "print(db.measurements.countDocuments({receivedAt: {\$gt: ISODate('$1')}, replayed: {\$exists: false}}))")" > 0 ))
}

check_outage() {
  local from until
  from="$(mongo_eval "print(new Date().toISOString())")"
  docker compose stop cloud-broker >/dev/null 2>&1 || return 1
  broker_stopped=1
  sleep "$OUTAGE_SECONDS"
  until="$(mongo_eval "print(new Date().toISOString())")"
  docker compose start cloud-broker >/dev/null 2>&1 || return 1
  broker_stopped=""

  wait_until 120 replay_complete "$from" "$until" || {
    printf '  Readings from the outage were not all replayed into MongoDB\n'
    return 1
  }
  wait_until 60 live_after "$until" || { printf '  Live readings did not resume\n'; return 1; }
}

check_result "all six Compose services are running" services_running
check_result "telemetry arrives from all ten sensors on all three sites" check_telemetry
check_result "MongoDB ping and unique msgId index" check_mongo
check_result "API answers on /api/health" check_api
check_result "readings from all ten sensors reach MongoDB" check_storage
check_result "gateway status reaches MongoDB" check_gateway_status
check_result "duplicates are ignored and bad messages rejected" check_duplicates_and_rejects
check_result "temperature fault raises a WebSocket alert in under 10 s, then closes" check_alerts
check_result "readings survive a cloud outage and are replayed" check_outage

if (( failures > 0 )); then
  printf 'Smoke test failed: %s check(s) failed.\n' "$failures"
  exit 1
fi
printf 'All smoke checks passed.\n'
