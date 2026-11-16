#!/usr/bin/env bash
# One command for the whole local stack: prepares .env and certificates if
# needed, builds and starts everything, then runs the end-to-end checks.
set -euo pipefail

cd "$(dirname "$0")/.."
# Stops Git Bash on Windows from rewriting container paths.
export MSYS_NO_PATHCONV=1

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example."
fi

# Login accounts (middleware/iam): a random password when the value is missing, empty or still a "change-me"
# example, which the login server refuses. A value someone chose is never changed. Same rule as start.ps1.
for name in IAM_ADMIN_PASSWORD IAM_QUALITY_PASSWORD IAM_OPERATOR_PASSWORD; do
  value="$(grep -E "^$name=" .env | head -1 | cut -d= -f2- | tr -d '\r' || true)"
  if [[ -z "$value" || "$value" == change-me* ]]; then
    secret="$(openssl rand -hex 16)"
    if grep -qE "^$name=" .env; then
      sed -i.bak "s/^$name=.*/$name=$secret/" .env && rm -f .env.bak
    else
      printf '%s=%s\n' "$name" "$secret" >> .env
    fi
    echo "Generated $name in .env (login: see middleware/iam/README.md)."
  fi
done

if [[ -d certs/ca.crt ]]; then
  echo "certs/ca.crt is a folder, left by an earlier start without certificates." >&2
  echo "Run: docker compose down   then delete that folder and run this script again." >&2
  exit 1
fi

if [[ ! -f certs/ca.crt || ! -f certs/cloud-broker.crt || ! -f certs/cloud-broker.key ]]; then
  bash scripts/gen-certs.sh
fi

docker compose up -d --build
bash scripts/smoke-test.sh
