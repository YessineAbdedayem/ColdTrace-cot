#!/usr/bin/env bash
# Puts the Let's Encrypt certificate of DOMAIN into the WildFly keystore gateway-iot/certs/web.p12 (password:
# WEB_TLS_PASSWORD in gateway-iot/.env), then restarts WildFly if it runs. Called by setup.sh and, after each
# renewal, by certbot (deploy hook in /etc/letsencrypt/renewal-hooks/deploy/coldtrace.sh).
#
# Usage (root): deploy/ubuntu/web-cert.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE="$ROOT/gateway-iot/.env"
value() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r'; }
DOMAIN="$(value DOMAIN)"
export WEB_TLS_PASSWORD="$(value WEB_TLS_PASSWORD)"
LIVE="/etc/letsencrypt/live/$DOMAIN"
[[ -f "$LIVE/fullchain.pem" ]] || { echo "No certificate in $LIVE (run certbot first)." >&2; exit 1; }

openssl pkcs12 -export -in "$LIVE/fullchain.pem" -inkey "$LIVE/privkey.pem" -name coldtrace \
  -out "$ROOT/gateway-iot/certs/web.p12.new" -passout env:WEB_TLS_PASSWORD
mv "$ROOT/gateway-iot/certs/web.p12.new" "$ROOT/gateway-iot/certs/web.p12"
# WildFly runs as another user in its container; the keystore itself is password-protected
chmod 644 "$ROOT/gateway-iot/certs/web.p12"

if docker compose --project-directory "$ROOT/gateway-iot" -f "$ROOT/gateway-iot/docker-compose.yml" \
     -f "$ROOT/deploy/docker-compose.cloud.yml" ps --status running --services 2>/dev/null | grep -qx wildfly; then
  docker compose --project-directory "$ROOT/gateway-iot" -f "$ROOT/gateway-iot/docker-compose.yml" \
    -f "$ROOT/deploy/docker-compose.cloud.yml" restart wildfly
fi
echo "HTTPS keystore updated for $DOMAIN."
