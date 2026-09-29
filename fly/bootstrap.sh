#!/usr/bin/env bash
# Creates what the dogfood deploy needs on fly.io, and only what is missing. Safe to run on every
# deploy: an app, volume or address that already exists is left alone.
#
#   FLY_API_TOKEN   fly.io token for the organisation (dogfood environment secret)
#   NEO4J_PASSWORD  the graph database password, at least 8 characters (dogfood environment secret)
#   INGEST_TOKEN    optional: the token the deploy reports deployments with (dogfood environment secret)
#   ALERTMANAGER_WEBHOOK_URL  optional: where alerts are posted (dogfood environment secret)
#   FLY_APP_PREFIX  app names: <prefix> (web), <prefix>-backend, <prefix>-neo4j, <prefix>-monitoring.
#                   Default sdlc-graph
#   FLY_ORG         fly.io organisation slug. Default personal
#   FLY_REGION      fly.io region. Default lhr
set -euo pipefail

prefix="${FLY_APP_PREFIX:-sdlc-graph}"
org="${FLY_ORG:-personal}"
region="${FLY_REGION:-lhr}"
: "${NEO4J_PASSWORD:?NEO4J_PASSWORD is not set; add it to the dogfood environment (fly/README.md)}"
if [ "${#NEO4J_PASSWORD}" -lt 8 ]; then
  echo "NEO4J_PASSWORD must be at least 8 characters: Neo4j refuses a shorter one and does not start." >&2
  exit 1
fi

app_exists() { flyctl status --app "$1" >/dev/null 2>&1; }

for app in "$prefix-neo4j" "$prefix-backend" "$prefix" "$prefix-monitoring"; do
  if app_exists "$app"; then
    echo "app $app exists"
  else
    echo "creating app $app"
    flyctl apps create "$app" --org "$org"
  fi
done

if flyctl volumes list --app "$prefix-neo4j" --json | jq -e 'map(select((.name // .Name) == "neo4j_data")) | length > 0' >/dev/null; then
  echo "volume neo4j_data exists"
else
  echo "creating volume neo4j_data"
  flyctl volumes create neo4j_data --app "$prefix-neo4j" --region "$region" --size 1 --yes
fi

# The backend's only address is private. fly allocates public addresses on the first deploy of an app
# that has none, so the private one has to exist before that deploy, not after.
if flyctl ips list --app "$prefix-backend" --json | jq -e 'length > 0' >/dev/null; then
  echo "backend has an address"
else
  echo "allocating the backend's private flycast address"
  flyctl ips allocate-v6 --private --app "$prefix-backend"
fi

# Staged, so they are applied by the deploys that follow rather than restarting anything now.
# NEO4J_AUTH only sets the password when the database is first created; changing the secret later
# does not change the password of an existing graph (fly/README.md, "Changing the password").
flyctl secrets set --stage --app "$prefix-neo4j" "NEO4J_AUTH=neo4j/$NEO4J_PASSWORD" >/dev/null
flyctl secrets set --stage --app "$prefix-backend" "NEO4J_PASSWORD=$NEO4J_PASSWORD" >/dev/null
# Without it the backend answers the ingest endpoint with 503, and the deploy reports nothing (#7).
if [ -n "${INGEST_TOKEN:-}" ]; then
  flyctl secrets set --stage --app "$prefix-backend" "INGEST_TOKEN=$INGEST_TOKEN" >/dev/null
fi
# Without it Alertmanager has nowhere to send alerts, and the deploy skips its delivery check (#47).
if [ -n "${ALERTMANAGER_WEBHOOK_URL:-}" ]; then
  flyctl secrets set --stage --app "$prefix-monitoring" "ALERTMANAGER_WEBHOOK_URL=$ALERTMANAGER_WEBHOOK_URL" >/dev/null
fi
echo "secrets staged"
