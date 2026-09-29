#!/usr/bin/env bash
# Checks the deployed dogfood instance from the outside, and fails the deploy if it is not what it
# should be: serving, answering reads, refusing writes, and with nothing but the web interface public.
# It also waits out the cold start, so the conformance suite that runs next (e2e/conformance, the
# contract in docs/DEPLOYMENT.md) meets a warm instance. This script keeps what needs flyctl: D7.
#
#   verify.sh <prefix>     e.g. verify.sh sdlc-graph   (checks https://sdlc-graph.fly.dev)
set -euo pipefail

prefix="${1:-${FLY_APP_PREFIX:-sdlc-graph}}"
base="https://$prefix.fly.dev"
failures=0

pass() { echo "ok   $1"; }
fail() {
  echo "FAIL $1" >&2
  failures=$((failures + 1))
}

# Machines stop when idle and start on the first request, and a cold JVM takes a while, so the first
# read is allowed a few minutes before it counts as a failure.
status_of() { curl -s -o /dev/null -w '%{http_code}' --max-time 60 "$@"; }
wait_for_200() {
  local url=$1 deadline=$((SECONDS + 300))
  while [ $SECONDS -lt $deadline ]; do
    [ "$(status_of "$url")" = 200 ] && return 0
    sleep 10
  done
  return 1
}

if wait_for_200 "$base/healthz"; then pass "the web interface is serving ($base)"; else fail "$base/healthz never answered 200"; fi
if wait_for_200 "$base/api/v1/ontology"; then pass "the API answers a read through the web interface"; else fail "$base/api/v1/ontology never answered 200"; fi
if wait_for_200 "$base/api/v1/nodes/Repository"; then pass "the API reads the graph database"; else fail "$base/api/v1/nodes/Repository never answered 200"; fi

write=$(status_of -X POST -H 'Content-Type: application/json' -d '{"props":{"name":"verify"}}' "$base/api/v1/nodes/Team")
if [ "$write" = 403 ]; then pass "a write from the internet is refused"; else fail "a write from the internet answered $write, not 403"; fi

# The database, the API and the monitoring have no public address (#48 D7, D9): only private ones
# may be listed.
for app in "$prefix-neo4j" "$prefix-backend" "$prefix-monitoring"; do
  public=$(flyctl ips list --app "$app" --json | jq '[.[] | select(((.Type // .type) | ascii_downcase) != "private_v6")] | length')
  if [ "$public" = 0 ]; then pass "$app has no public address"; else fail "$app has $public public address(es)"; fi
done

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed against $base" >&2
  exit 1
fi
echo "all checks passed against $base"
