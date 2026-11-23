#!/usr/bin/env bash
# First installation of ColdTrace on a fresh Ubuntu server (22.04 or 24.04, x86-64 or arm64), from GitHub.
#
#   curl -fsSLO https://raw.githubusercontent.com/<owner>/<repo>/main/deploy/ubuntu/setup.sh
#   sudo bash setup.sh --domain coldtrace.example.com --email ops@example.com --repo https://github.com/<owner>/<repo>.git
#
# Before: DNS A records for DOMAIN, www., api., iam., admin., mlflow. and mqtt. pointing at this server; ports 22, 80,
# 443 and 8883 open in the cloud provider's firewall. Without DNS yet, add --self-signed (development CA).
#
# What it does (each step is skipped when already done, so it can run again):
#   1. installs Docker Engine + Compose, git, openssl, certbot and the ufw firewall (SSH, 80, 443, 8883);
#   2. clones the repository into /opt/coldtrace, owned by the "coldtrace" user (member of "docker");
#   3. writes gateway-iot/.env with random passwords and the domain;
#   4. creates the MQTTS certificates (private CA; sites' gateways get ca.crt) and the HTTPS certificate
#      (Let's Encrypt, renewed automatically);
#   5. builds and starts the stack, then waits for the API.
# Updates afterwards: /opt/coldtrace/deploy/ubuntu/update.sh (git pull + rebuild), by hand or from GitHub Actions.
set -euo pipefail

DOMAIN="" EMAIL="" REPO="" BRANCH="main" SELF_SIGNED=0 DIR=/opt/coldtrace APP_USER=coldtrace
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain) DOMAIN="$2"; shift 2 ;;
    --email) EMAIL="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --self-signed) SELF_SIGNED=1; shift ;;
    *) echo "Unknown option $1" >&2; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
[[ "$DOMAIN" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$ ]] || { echo "--domain: a lowercase domain name, e.g. coldtrace.example.com" >&2; exit 2; }
[[ -n "$EMAIL" ]] || { echo "--email: the contact for Let's Encrypt and Web Push" >&2; exit 2; }
[[ -n "$REPO" || -d "$DIR/.git" ]] || { echo "--repo: the git URL to clone" >&2; exit 2; }

say() { printf '\n\033[1m>>> %s\033[0m\n' "$*"; }

# ---------- 1. packages and firewall ----------
say "Packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q ca-certificates curl git openssl ufw certbot
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

say "Firewall: SSH, HTTP, HTTPS, MQTTS"
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 8883/tcp >/dev/null
ufw --force enable >/dev/null
# Docker publishes ports past ufw's INPUT rules; only 80, 443 and 8883 are published on all interfaces
# (deploy/docker-compose.cloud.yml), the others are bound to 127.0.0.1.

# ---------- 2. code ----------
say "Code in $DIR"
id "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /bin/bash "$APP_USER"
usermod -aG docker "$APP_USER"
if [[ ! -d "$DIR/.git" ]]; then
  git clone --branch "$BRANCH" "$REPO" "$DIR"
fi
chown -R "$APP_USER:$APP_USER" "$DIR"
as_app() { sudo -u "$APP_USER" -H "$@"; }
cd "$DIR"

# ---------- 3. settings ----------
say "Settings (gateway-iot/.env)"
ENV_FILE=gateway-iot/.env
if [[ ! -f "$ENV_FILE" ]]; then
  as_app cp gateway-iot/.env.example "$ENV_FILE"
  chmod 600 "$ENV_FILE"
fi
set_var() {   # set_var NAME VALUE: replace or append
  if grep -qE "^$1=" "$ENV_FILE"; then sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"; else printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"; fi
}
get_var() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '\r'; }
# every password still at its example value (or empty) gets a random one; values someone chose are kept
for name in CLOUD_GATEWAY_PASSWORD CLOUD_BACKEND_PASSWORD MONGO_ROOT_PASSWORD MONGO_APP_PASSWORD WEB_TLS_PASSWORD \
            IAM_ADMIN_PASSWORD IAM_QUALITY_PASSWORD IAM_OPERATOR_PASSWORD; do
  current="$(get_var "$name")"
  if [[ -z "$current" || "$current" == change-me* ]]; then set_var "$name" "$(openssl rand -hex 18)"; fi
done
set_var DOMAIN "$DOMAIN"
set_var PUSH_SUBJECT "mailto:$EMAIL"
chown "$APP_USER:$APP_USER" "$ENV_FILE"

# ---------- 4. certificates ----------
say "MQTTS certificates (private CA)"
if [[ ! -f gateway-iot/certs/cloud-broker.crt ]]; then
  as_app bash gateway-iot/scripts/gen-certs.sh "$DOMAIN" "mqtt.$DOMAIN"
fi

say "HTTPS certificate"
if [[ $SELF_SIGNED -eq 1 ]]; then
  [[ -f gateway-iot/certs/web.p12 ]] || as_app env WEB_TLS_PASSWORD="$(get_var WEB_TLS_PASSWORD)" bash middleware/wildfly/gen-web-cert.sh "$DOMAIN"
else
  COMPOSE="docker compose --project-directory $DIR/gateway-iot -f $DIR/gateway-iot/docker-compose.yml -f $DIR/deploy/docker-compose.cloud.yml"
  if [[ ! -d "/etc/letsencrypt/live/$DOMAIN" ]]; then
    # standalone: certbot answers the challenge on port 80 itself, before WildFly runs
    certbot certonly --standalone --non-interactive --agree-tos -m "$EMAIL" \
      -d "$DOMAIN" -d "www.$DOMAIN" -d "api.$DOMAIN" -d "iam.$DOMAIN" -d "admin.$DOMAIN" -d "mlflow.$DOMAIN" \
      --pre-hook "$COMPOSE stop wildfly || true" --post-hook "$COMPOSE start wildfly || true"
  fi
  install -m 0755 -d /etc/letsencrypt/renewal-hooks/deploy
  printf '#!/bin/sh\nexec bash %s/deploy/ubuntu/web-cert.sh\n' "$DIR" > /etc/letsencrypt/renewal-hooks/deploy/coldtrace.sh
  chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/coldtrace.sh
  bash deploy/ubuntu/web-cert.sh
  chown "$APP_USER:$APP_USER" gateway-iot/certs/web.p12
fi

# ---------- 5. start ----------
say "Build and start (first build: about 10 minutes)"
as_app bash deploy/ubuntu/render-config.sh "$DOMAIN"
as_app docker compose --project-directory gateway-iot -f gateway-iot/docker-compose.yml -f deploy/docker-compose.cloud.yml up -d --build

CURL_OPTS=(-fsS --max-time 5)
[[ $SELF_SIGNED -eq 1 ]] && CURL_OPTS+=(--cacert gateway-iot/certs/ca.crt --resolve "api.$DOMAIN:443:127.0.0.1")
healthy=0
for i in $(seq 1 90); do
  if curl "${CURL_OPTS[@]}" "https://api.$DOMAIN/api/health" >/dev/null 2>&1; then healthy=1; break; fi
  sleep 5
done
[[ $healthy -eq 1 ]] || echo "WARNING: the API is not answering yet. Logs: docker compose ... logs wildfly" >&2

cat <<EOF

ColdTrace is running on $DOMAIN.
  Monitor (front office)   https://www.$DOMAIN
  Back office              https://admin.$DOMAIN
  API                      https://api.$DOMAIN/api/health
  MQTTS for the gateways   mqtt.$DOMAIN:8883   (they need gateway-iot/certs/ca.crt and their site login)

Logins: admin / qualite / operateur, passwords in $DIR/$ENV_FILE (IAM_*_PASSWORD; readable by root and $APP_USER).
Updates: sudo -u $APP_USER $DIR/deploy/ubuntu/update.sh   (or the "Deploy" GitHub Actions workflow)
EOF
