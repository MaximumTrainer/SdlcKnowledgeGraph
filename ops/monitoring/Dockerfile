# Prometheus and Alertmanager in one image, for the dogfood instance (#47 FR12) and the monitoring
# compose profile. The rules and routes are copied in at build time, so the image deployed from a
# commit evaluates exactly that commit's ops/alerts and routes with its ops/alertmanager.
#
# Build context: ops/
FROM prom/alertmanager:v0.28.1 AS alertmanager

FROM prom/prometheus:v3.5.0
COPY --from=alertmanager /bin/alertmanager /bin/amtool /bin/
COPY alerts/app.rules.yml /etc/prometheus/rules/
COPY alerts/generated/ /etc/prometheus/rules/generated/
COPY --chown=nobody:nobody alertmanager/alertmanager.yml /etc/alertmanager/alertmanager.yml
COPY monitoring/prometheus.yml /etc/prometheus/prometheus.template.yml
COPY monitoring/entrypoint.sh /bin/monitoring
COPY monitoring/deploy-check.sh /bin/deploy-check
EXPOSE 9090 9093
ENTRYPOINT ["/bin/monitoring"]
