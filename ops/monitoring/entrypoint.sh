#!/bin/sh
# Starts Alertmanager and Prometheus side by side (#47 FR12), and stops the container when either
# stops, so the platform restarts both rather than leaving half a monitor running.
#
#   BACKEND_ADDR              host:port of the API to scrape, e.g. sdlc-graph-backend.internal:8080
#   ALERTMANAGER_WEBHOOK_URL  where alerts are posted (a secret). Without it nothing is delivered.
set -eu

: "${BACKEND_ADDR:?BACKEND_ADDR is not set: the host:port of the API to scrape}"
sed "s|__BACKEND_ADDR__|$BACKEND_ADDR|" /etc/prometheus/prometheus.template.yml >/etc/prometheus/prometheus.yml

# ops/alertmanager/alertmanager.yml reads the URL from this file, so it never sits in a config.
if [ -n "${ALERTMANAGER_WEBHOOK_URL:-}" ]; then
  printf '%s' "$ALERTMANAGER_WEBHOOK_URL" >/etc/alertmanager/webhook-url
else
  echo "warning: ALERTMANAGER_WEBHOOK_URL is not set, so no alert will be delivered" >&2
fi

mkdir -p /prometheus/alertmanager
# No cluster: there is one Alertmanager, and gossip has nothing to find.
alertmanager --config.file=/etc/alertmanager/alertmanager.yml --storage.path=/prometheus/alertmanager \
  --web.listen-address=:9093 --cluster.listen-address= &
alertmanager=$!
prometheus --config.file=/etc/prometheus/prometheus.yml --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=15d --web.listen-address=:9090 &
prometheus=$!

stop() {
  kill "$alertmanager" "$prometheus" 2>/dev/null || true
  wait
  exit "$1"
}
trap 'stop 0' TERM INT
while kill -0 "$alertmanager" 2>/dev/null && kill -0 "$prometheus" 2>/dev/null; do
  sleep 5
done
echo "Alertmanager or Prometheus exited; stopping both" >&2
stop 1
