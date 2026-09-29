---
name: observability-change
description: Add or change a log event, a metric, a dashboard panel, a service objective, an alert, a runbook or an Alertmanager route in this repository, and prove it with the checks that hold each one in place. Use whenever a change touches events.yaml, a Micrometer meter, ops/grafana, ops/slo.yaml, ops/alerts, ops/alertmanager or docs/runbooks.
---

# Change what the service tells its operators

Every signal here is declared once and checked by the build (#44). Changing one means changing the
declaration, regenerating what is generated, and adding the case that proves it. The reference is
`docs/OBSERVABILITY.md`; this is the order to work in.

## A log line

1. Declare the event in `backend/src/main/resources/observability/events.yaml`: a dot-separated name,
   a level, a fixed message with no `{}` placeholders, a description, typed fields. Name what
   happened, never the data: log a property's name, not its value.
2. `node scripts/gradle.mjs generateLogEvents`, then call the generated `LogEvents.<name>(...)`.
   Never obtain an SLF4J logger; `LoggingConventionsTest` fails the build if you do.
3. Assert the event in the acceptance or unit test that drives the behaviour. If a field could carry
   a secret, check that it is masked (`SensitiveFieldMasker`).

## A metric

1. Record it through a small `@Component` in `observability/` (see `GraphWriteMetrics`), not with a
   `MeterRegistry` scattered through the services. Name it `sdlc.<thing>` and count outcomes with a
   tag rather than one meter per outcome.
2. Keep every tag's values bounded: an ontology type, an enum, a port operation. Never an id, a key,
   a URL with parameters or a user-supplied string.
3. Register the series at zero when an alert will read it, so the alert has a series before the
   first event (see `InstrumentedGraphStore`).
4. Test it through `/actuator/prometheus` (`metrics.feature`, `PrometheusScrapeIT`) and add a row to
   the Metrics table in `docs/OBSERVABILITY.md`.
5. If an operator should watch it, add a panel to `ops/grafana/dashboards/sdlc-sync-dashboard.json`
   that queries the `prometheus` datasource. `node scripts/dashboard.mjs` fails while a panel reads a
   metric the Metrics table does not list, so renaming or removing a meter means changing the
   dashboard in the same commit. Check it renders: `docker compose --profile monitoring up -d
   --build --wait`, then http://localhost:3000.

## An objective or an alert

1. An objective goes in `ops/slo.yaml`; run `node scripts/slo-rules.mjs` and commit
   `ops/alerts/generated/slo.rules.yml`. Never edit the generated file.
2. Any other alert goes in `ops/alerts/app.rules.yml`, with a `severity` of `page` (someone must act
   now) or `ticket` (it can wait), and an `annotations.runbook_url` naming its page.
3. Write `docs/runbooks/<name>.md` with the sections the runbooks README lists.
   `AlertRunbookTest` fails while an alert and its runbook are not paired.
4. Add promtool cases in `ops/alerts/tests/`: one where the alert fires and one where it does not,
   and one per clause if the expression has several. Break the rule on purpose and see a case fail.
5. Add a case to `ops/alertmanager/tests/routes.test.yml` naming the receiver the alert should reach.

## A route or an inhibition

Edit `ops/alertmanager/alertmanager.yml` and its cases in `ops/alertmanager/tests/routes.test.yml`.
Webhooks read their URL from `url_file`; never commit a URL. An inhibit rule is checked by
`scripts/alertmanager-config.test.mjs`, since amtool cannot evaluate one: extend that test with it.

## Prove it

```bash
node scripts/slo-rules.mjs --check && node scripts/promtool.mjs   # rules, promtool and amtool cases
node scripts/dashboard.mjs                                       # dashboard reads documented metrics
npm run test:unit                                               # the alertmanager config test
node scripts/gradle.mjs check                                   # event drift, runbooks, meters
```

promtool and amtool run from pinned images, so Docker must be running. To watch the rules evaluate
against a running API, `docker compose --profile monitoring up -d --build --wait` and open
http://localhost:9090/alerts; `docker compose exec monitoring deploy-check` proves delivery. The
monitoring image copies in `ops/alerts` and `ops/alertmanager` at build time, so a new rule file
under another directory needs a `COPY` line in `ops/monitoring/Dockerfile`. Then update the matching
section of `docs/OBSERVABILITY.md`, and run the verify-gate skill before pushing.
