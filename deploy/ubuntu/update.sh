#!/usr/bin/env bash
# Updates a running ColdTrace server to the latest commit of its branch: git pull, rebuild, restart, health check.
# Run by hand on the server, or by the "Deploy" GitHub Actions workflow over SSH.
#
# Usage: /opt/coldtrace/deploy/ubuntu/update.sh       (as the user that owns /opt/coldtrace, member of "docker")
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose --project-directory gateway-iot -f gateway-iot/docker-compose.yml -f deploy/docker-compose.cloud.yml)
DOMAIN="$(grep -E '^DOMAIN=' gateway-iot/.env | tail -1 | cut -d= -f2- | tr -d '\r')"

before="$(git rev-parse --short HEAD)"
git fetch --quiet origin
git merge --ff-only --quiet "@{upstream}"
after="$(git rev-parse --short HEAD)"
echo "Code: $before -> $after"

bash deploy/ubuntu/render-config.sh "$DOMAIN"
"${COMPOSE[@]}" up -d --build --remove-orphans
docker image prune -f >/dev/null

# The API answers once WildFly, MongoDB and the IAM key are ready (a few tens of seconds after a rebuild).
for i in $(seq 1 60); do
  if curl -fsS --max-time 5 "https://api.$DOMAIN/api/health" >/dev/null 2>&1; then
    echo "Healthy: https://api.$DOMAIN/api/health ($after)"
    exit 0
  fi
  sleep 5
done
echo "The API did not become healthy in 5 minutes. Logs: ${COMPOSE[*]} logs --tail 200 wildfly" >&2
exit 1
