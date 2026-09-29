#!/bin/sh
# Run inside the monitoring container after a deploy (#47 FR12, docs/DEPLOYMENT.md D10). Fails
# unless Prometheus is scraping the API, has loaded the rules and is connected to Alertmanager, and a
# synthetic alert is delivered to the webhook receiver without error.
#
# The synthetic alert is DeployCheck, severity ticket: whoever reads the receiver will see one per
# deploy, and it resolves on its own.
set -eu

prom=http://localhost:9090
am=http://localhost:9093
deadline=$(($(date +%s) + ${DEPLOY_CHECK_TIMEOUT:-180}))

# Retries a check until it passes or the deadline is reached.
check() {
  description=$1
  shift
  until "$@"; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "FAIL $description" >&2
      exit 1
    fi
    sleep 5
  done
  echo "ok   $description"
}

scraping() {
  wget -q -O - "$prom/api/v1/query?query=up%7Bjob%3D%22sdlc-graph-backend%22%7D" | grep -q '"value":\[[0-9.e+]*,"1"\]'
}
rules_loaded() {
  rules=$(wget -q -O - "$prom/api/v1/rules") &&
    echo "$rules" | grep -q '"name":"InstanceDown"' &&
    echo "$rules" | grep -q '"name":"AvailabilityBudgetFastBurn"'
}
connected() {
  wget -q -O - "$prom/api/v1/alertmanagers" | grep -q '"url":"http://localhost:9093/api/v2/alerts"'
}
# Sums an Alertmanager counter over the webhook integration.
webhook_total() {
  wget -q -O - "$am/metrics" |
    awk -v name="$1" 'index($1, name "{") == 1 && $1 ~ /integration="webhook"/ { sum += $2 } END { print sum + 0 }'
}

check "Prometheus is scraping the API" scraping
check "Prometheus has loaded the alert rules" rules_loaded
check "Prometheus is connected to Alertmanager" connected

# A request counts as delivered once it has completed (its latency is recorded) and not failed. A
# failing webhook is retried, so it shows up as requests that never complete successfully.
delivered_total() {
  echo $(($(webhook_total alertmanager_notification_latency_seconds_count) - $(webhook_total alertmanager_notification_requests_failed_total)))
}
before=$(delivered_total)
amtool alert add alertname=DeployCheck severity=ticket job=deploy-check \
  --annotation='summary="Deploy check: this alert proves alerts reach this receiver"' \
  --alertmanager.url="$am"
# Read twice, a moment apart, so a request caught between completing and being counted as failed is
# not mistaken for a delivery.
delivered() {
  [ "$(delivered_total)" -gt "$before" ] && sleep 2 && [ "$(delivered_total)" -gt "$before" ]
}
check "the webhook accepted a synthetic alert" delivered
