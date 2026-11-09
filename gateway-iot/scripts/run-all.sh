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
