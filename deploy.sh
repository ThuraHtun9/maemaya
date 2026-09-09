#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "Missing .env file. Run: cp .env.example .env and edit values first."
  exit 1
fi

COMPOSE_FILE="docker-compose.prod.yml"

set -a
source .env
set +a

require_var() {
  local name="$1"
  local value="${!name:-}"
  if [[ -z "$value" ]]; then
    echo "Missing required env: $name"
    exit 1
  fi
}

reject_placeholder() {
  local name="$1"
  local value="${!name:-}"
  if [[ "$value" == *"CHANGE_THIS"* || "$value" == "shop.example.com" || "$value" == "example.com" ]]; then
    echo "Invalid placeholder value for: $name"
    exit 1
  fi
}

require_var DOMAIN
require_var ADMIN_USERNAME
require_var ADMIN_PASSWORD
require_var DB_NAME
require_var DB_USER
require_var DB_PASSWORD

reject_placeholder DOMAIN
reject_placeholder ADMIN_PASSWORD
reject_placeholder DB_PASSWORD

if [[ "$ADMIN_PASSWORD" == "admin" ]]; then
  echo "ADMIN_PASSWORD must not be 'admin' in production."
  exit 1
fi

# Keep data in project-local persistent folders.
mkdir -p ./uploads ./data ./data/postgres

# Ensure uploads path is writable by app user inside container (uid 10001).
chown -R 10001:10001 ./uploads 2>/dev/null || true
chmod -R u+rwX,go+rX ./uploads 2>/dev/null || true

docker compose -f "$COMPOSE_FILE" --env-file .env up -d --build

echo "Deployment started."
echo "Check status: docker compose -f $COMPOSE_FILE ps"
echo "Keep existing data by using the same ./data and ./uploads folders."
echo "Do not run 'docker compose down -v' unless you intentionally want to delete data."
