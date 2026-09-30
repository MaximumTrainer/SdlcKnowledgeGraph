# Observability

What the application says about itself, and how to follow one request through it. This is being
built in parts under [#44](../../issues/44): request correlation, the log format, the declared
event registry, the metrics, the service objectives and their alerts are here; routing alerts to a
receiver comes next.

## Following a request

Every request carries a correlation id. A client may send its own as `X-Request-Id`; it is used when
it matches `^[A-Za-z0-9._-]{1,64}$`, and replaced with a fresh one otherwise, because the id is
written into every log line and a newline or a quote in it would let a caller forge log entries. The
response always carries the id that was used in its own `X-Request-Id` header, so a caller who
reports a problem can quote it.

The web interface's nginx passes the caller's `X-Request-Id` to the API, or its own `$request_id`
when there is none, so the id is the same at the edge and in the API's logs.

The id is assigned before anything else runs, the read-only guard included, so a refused request can
be traced as well as a served one. It lives in the logging MDC as `requestId` for the length of the
request and is removed afterwards, so a pooled thread never stamps one request's lines with
another's id.

## Log format

Under the `docker` profile, which is how every container runs, the API writes one JSON object per
line to standard output:

```json
{"@timestamp":"2026-09-29T17:37:04.010Z","level":"INFO","logger":"com.repodatagraph...","thread":"http-nio-8080-exec-1",
 "message":"...","requestId":"abc-123","service":"sdlc-graph-backend","version":"<commit>"}
```

`version` is the commit the image was built from (`SDLC_COMMIT`). A throwable is in its own
`stack_trace` field rather than inside `message`. Nothing that is not JSON is written, so a platform
can aggregate the output without parsing a human format; the CI end-to-end job fails if a line is
not JSON or a request id does not come back through nginx. The one exception is the JVM's own
`Picked up JAVA_TOOL_OPTIONS: …` line on stderr, printed before any logger exists.

Outside the `docker` profile, for example `./gradlew bootRun`, the format is Spring Boot's readable
one, with the request id in brackets after the level.

## Events

The application's own log lines are declared, not written ad hoc. Each is an entry in
[`events.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/observability/events.yaml):
a dot-separated name such as `node.created`, a level, a fixed message, a description and typed
fields. The build generates one function per event in `observability/LogEvents.kt`, so a caller
writes `LogEvents.nodeCreated(type, key, properties)` and cannot invent a field, a level or a
message. It also generates `observability/events.json`, the catalogue a log platform or an alert
rule can read.

A logged event carries its name in an `event` field, its fields as top-level keys beside
`requestId`, and a logger of `com.repodatagraph.event.<name>`, so one event can be turned up or down
on its own (`logging.level.com.repodatagraph.event.node.created=WARN`). An event marked
`security: true` carries the `SECURITY` marker, which the JSON output lists under `tags`.

Three things keep it that way:

- `LoggingConventionsTest` fails if any class other than `EventLog` obtains an SLF4J logger, or
  anything outside the generated `LogEvents` calls one.
- `./gradlew logEventDriftCheck`, part of `check` and the pre-commit hook, fails when `events.yaml`
  and the generated files disagree. Run `./gradlew generateLogEvents` and commit the result.
- The generator refuses an event with an unknown level or type, a duplicate name or field, a message
  with `{}` placeholders, or a field named `event` or `cause`.

To add an event: declare it in `events.yaml`, run `./gradlew generateLogEvents`, and call the new
function. Framework and library logs (Spring, the Neo4j driver) are not events and keep their own
loggers and levels.

### What never reaches a log

An event names what happened, not the data it happened to. A node write logs the node's type, its
key and the names of the properties supplied, never their values; a refused write logs the names of
the fields at fault. On top of that, every field value passes through `SensitiveFieldMasker` on the
way out: a field whose name contains `password`, `token`, `secret`, `authorization`, `apikey` (or
`api-key`, `api_key`), `cookie` or `credential`, in any case and at any depth of a map, is written
as `***`. The acceptance suite checks that a secret posted in a node or an ingest token never appears
in any log line.

## Metrics

The API publishes its meters in Prometheus's text format at `/actuator/prometheus`. That endpoint is
for a scraper inside the deployment: the web interface's nginx does not proxy it, so it cannot be
read from the public internet ([DEPLOYMENT.md](DEPLOYMENT.md), D9), and the CI end-to-end job checks
both halves of that.

| Metric | Labels | What it counts |
|---|---|---|
| `sdlc_build_info` | `version`, `ontology_version`, `commit` | Always 1. Join any series to it to see which build produced it. A value that is not known reads `unknown`. |
| `sdlc_node_writes_total` | `type`, `outcome` | Node writes through the API. `outcome` is `created`, `updated`, `deleted` or `rejected` (refused by the ontology, so nothing was stored). |
| `sdlc_edge_writes_total` | `type`, `outcome` | The same for edges. Restating an edge that exists counts as `updated`. |
| `sdlc_graph_store_errors_total` | `operation` | Graph store operations that failed, such as when Neo4j is unreachable, by port operation (`upsertNode`, `findNode`, ...). Each failure is also logged as `graph.store.failed` with the request's id. Present at zero for every operation from startup, so an alert has a series before the first failure. |
| `http_server_requests_seconds` | Spring's (`method`, `uri`, `status`, `outcome`, ...) | Every request's duration, as a histogram with a bucket at 0.5 s, the latency objective. |
| `sdlc_dependency_up` | `dependency` | 1 while the dependency's health check is UP, otherwise 0. Only `neo4j` today. |
| `sdlc_sync_runs_total` | `connector`, `sourceSystem`, `mode`, `status` | Connector runs that finished. `mode` is `FULL`, `INCREMENTAL` or `WEBHOOK`; `status` is `SUCCESS`, `PARTIAL` (a page failed, the rest were kept) or `FAILED`. Present at zero for every mode a connector supports. |
| `sdlc_sync_duration_seconds` | `connector`, `sourceSystem`, `mode` | How long runs took, from the first page to the last, as a histogram with buckets at 1, 5, 30, 120 and 600 s. |
| `sdlc_sync_nodes_upserted_total` | `connector`, `sourceSystem` | Nodes runs wrote, as their `SyncRun` records them. |
| `sdlc_sync_edges_upserted_total` | `connector`, `sourceSystem` | Edges runs wrote. |
| `sdlc_sync_tombstones_total` | `connector`, `sourceSystem` | Facts runs closed, whether the connector reported the tombstone or a full sync's reconciliation found the fact gone. |
| `sdlc_sync_pages_total` | `connector`, `sourceSystem` | Pages read from connectors, including a page read but not written. Webhooks are not pages. |
| `sdlc_sync_errors_total` | `connector`, `sourceSystem`, `kind` | What went wrong. `page`: a page could not be read or written, so the run is partial. `run`: a run failed as a whole, or a webhook could not be written. `webhook_signature`: a webhook was refused for its signature. There is no `throttle` kind yet, because nothing tells a source's rate limiting apart from other failures. |
| `sdlc_sync_freshness_seconds` | `connector`, `sourceSystem` | Seconds since the connector's last successful scheduled or manual run finished; `NaN` if it has never succeeded. A webhook run does not reset it. Kept in memory and seeded from `ConnectorState.lastSuccessAt` on the first scrape after a restart (from `lastFinishedAt` for a state written before #29 added it). |
| `sdlc_sync_in_progress` | `connector`, `sourceSystem` | 1 while a run of the connector is going, otherwise 0. |
| `sdlc_webhook_events_total` | `connector`, `result` | Webhooks by what became of them: `applied` (it became a run, whatever that run's status), `ignored` (the connector found nothing in it, or it was a redelivery of one already applied) or `rejected` (its signature did not check out). |
| `sdlc_graph_nodes` | `type` | Nodes of each type the ontology declares, closed facts included, as last counted. Counted at startup and then every `observability.graph-count-interval` (`GRAPH_COUNT_INTERVAL`, default `PT5M`), never by a scrape; `NaN` until the first count. A count that fails keeps the last values and logs `graph.count.failed`. |
| `sdlc_graph_edges` | `type` | The same for each relationship type the ontology declares. |

`type` is always a type the ontology declares, because an undeclared one is refused before anything
is counted, so the label's cardinality is the size of the ontology. A refusal the store is designed
to give, such as an edge whose end does not exist, is an answer rather than a failure and is not
counted as a store error.

Writes that arrive through a connector or an ingest endpoint are not counted in the node and edge
write totals; a connector's writes are counted by the `sdlc_sync_*` meters instead (#29). Their
`connector` label only ever holds the name of a connector the adapter registry knows, so its
cardinality is the number of connectors, and every connector's series exist from startup. Each run
also logs `sync.started`, one `sync.page` per page applied and `sync.finished`, with the run's id in
`syncRunId`.

## Sync run retention

Every connector run leaves a `SyncRun` node and a `PRODUCED` edge to each node it wrote
([ADAPTERS.md](ADAPTERS.md#what-a-run-records)), so the history would grow with every sync for as
long as the instance ran. `SyncRunRetentionJob` prunes it (#29, FR6):

| Property | Default | Meaning |
| --- | --- | --- |
| `observability.sync-run-retention` (`SYNC_RUN_RETENTION`) | `P30D` | How long a finished run is kept, counted from its `finishedAt`. Must be positive. |
| `observability.sync-run-retention-cron` (`SYNC_RUN_RETENTION_CRON`) | `0 30 3 * * *` | When the prune runs, in Spring's six-field cron: 03:30 every night by default. |

What it deletes is narrow on purpose:

- Only `SyncRun` nodes that have finished. A `RUNNING` run is never pruned, however old: it may still
  be going, and if it is stuck the history is where someone will look for it.
- Their relationships, which are their `PRODUCED` edges. The nodes at the other end stay, and keep
  the run's id in `prov_syncRunId`, so where a fact came from is not forgotten along with the run.

It deletes a batch of 1000 runs at a time until a batch comes back short, and detaches each batch's
edges 1000 at a time before deleting the runs. A full sync of a large estate can produce tens of
thousands of nodes, and one transaction deleting a thousand such runs with all their edges is what
would outgrow a 512 MB instance's heap.

Each prune logs `sync.runs.pruned` with `deleted` and `olderThan`, even when it deleted nothing, so
a quiet night still shows the job ran. A failure logs `sync.runs.prune.failed` with the exception and
how many it had deleted; the next night's run is the retry. The prune is an internal write, not an
HTTP request, so a read-only deployment (`sdlc.read-only`) prunes too: that posture refuses writes
arriving over HTTP, and an instance nobody may write to still should not fill up.

`sdlc_graph_nodes` and `sdlc_graph_edges` say how big the graph is, which the connectors' own
counters cannot: a connector that rewrites the same thousand nodes every run and one that adds a
thousand new ones count the same. `GraphCountGauges` counts every declared type through the
`GraphCensus` port with one parameter-free Cypher statement per type (`MATCH (n:Team) RETURN
count(n)`), which Neo4j answers from its count store. A scrape never queries Neo4j: it reads the
last count, so the values can be as old as `observability.graph-count-interval`. They read `NaN`
rather than zero until the first count, because zero would claim the graph is empty when nobody has
looked, and the first count runs at startup, possibly before the application writes its own
`Ontology` node. A count that fails, because Neo4j is unreachable say, keeps every last value
rather than half of them and logs `graph.count.failed`.

## Health

`/actuator/health` is the API's health as a whole, one component per thing it depends on. Three of
them are this application's own:

| Component | UP | DOWN |
|---|---|---|
| `neo4j` | The database answers. | It does not; `sdlc_dependency_up{dependency="neo4j"}` is 0 too. |
| `connectors` | No enabled connector is stale, including when none is enabled at all. | At least one enabled connector has gone longer than its `freshness-threshold` without a successful run; `details.stale` names them ([ADAPTERS.md](ADAPTERS.md#freshness)). `UNKNOWN` when freshness cannot be read, usually because `neo4j` is down. |
| `freshness` | Every source is within its freshness window. | Never DOWN: `WARN` while a source is behind its window, and `UNKNOWN` when lag cannot be read. See below. |

A component that is DOWN makes the whole of `/actuator/health` DOWN, with status 503. The docker
profile shows which components there are but not their details, so `details.stale` is only visible
where `show-details` is turned on; the connectors API says the same thing per connector.

The liveness and readiness probes, `/actuator/health/liveness` and `/actuator/health/readiness`, are
separate groups, and the only health the web interface proxies ([DEPLOYMENT.md](DEPLOYMENT.md), D1).
Neither includes `connectors`: a stale graph still answers correctly about what it has, and a probe
that failed on it would take the instance out of service for something a restart cannot fix. Set
`observability.freshness-affects-readiness=true` (`FRESHNESS_AFFECTS_READINESS`) to add it to
readiness for a deployment that would rather serve nothing than serve stale answers; the probe then
still answers only a status, never which connector is behind.

### Source lag

The `freshness` component ([#93](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/93))
measures each source system, rather than each connector, against the freshness window its facts
have ([ONTOLOGY.md](ONTOLOGY.md#freshness-and-reading-as-of-an-instant)): the time since the latest
`finishedAt` of a `SUCCESS` sync run stamped with that source, of any mode. A webhook delivery counts
here, unlike for a connector's own freshness, because the question is when the source's facts were
last refreshed and a push refreshes them as a pull does. A source is listed once a run of it has
succeeded or an enabled connector stamps it; one whose connector has never succeeded is measured from
when the instance started, so a new deployment gets one window before it is called behind. Its
details, where `show-details` allows:

```json
"freshness": {
  "status": "WARN",
  "description": "a source is behind its freshness window",
  "details": {
    "lagging": ["github"],
    "sources": {
      "github": {
        "lastSuccessAt": "2026-09-29T06:00:00Z",
        "lag": "PT30H", "lagSeconds": 108000,
        "window": "PT24H", "windowSeconds": 86400,
        "lagging": true
      }
    }
  }
}
```

Lag must never take an instance down: fly.io checks `/actuator/health/readiness` and the compose
healthcheck runs `curl -fsS /actuator/health`, so a dogfood instance whose seed had not run for a day
would be restarted, or never become healthy. So the component is built to be unable to fail either:

- `WARN` is a status of its own, which Spring's status aggregation does not order. The overall status
  is what the other components say, `UP` while they are, and a lagging source never makes it DOWN.
- No HTTP status is mapped for `WARN`, so `/actuator/health` and `/actuator/health/freshness` answer
  `200` with it. Mapping one is deliberately not done: `management.endpoint.health.status.http-mapping`
  replaces Spring's defaults rather than adding to them, so mapping `WARN` alone would make `DOWN`
  answer `200` as well.
- Neither probe group includes it, and `observability.freshness-affects-readiness` adds only
  `connectors` to readiness, never `freshness`.
- A failure to read lag, Neo4j being down say, makes it `UNKNOWN`, which is not ordered either; the
  `neo4j` component is what reports the outage.

`FreshnessHealthIT` holds each of these against a real application context configured as the docker
profile is. The web interface proxies no health details, so `GET /api/v1/freshness` (scope
`graph:read`) answers the same per source, as `{sources: [{source, window, windowSeconds,
lastSuccessAt, lagSeconds, lagging}]}`.

There is no metric for it. `sdlc_sync_freshness_seconds` already gives each connector's age with its
`sourceSystem` label, so `min by (sourceSystem) (sdlc_sync_freshness_seconds)` is a source's lag as
its scheduled and manual runs see it, and a second gauge saying nearly the same would only disagree
with it about webhook runs.

## Service objectives

[`ops/slo.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/ops/slo.yaml) declares
what the API promises, over 30 days and leaving out `/actuator` traffic:

| Objective | Good request | Target |
|---|---|---|
| `availability` | answered without a server error | 99.5% |
| `latency` | answered within 500 ms | 99% |

The share of requests allowed to be bad is the error budget. `node scripts/slo-rules.mjs` turns each
objective into rules in `ops/alerts/generated/slo.rules.yml`: the error ratio recorded over 5m, 30m,
1h, 2h, 6h, 1d and 3d, and two alerts that fire on how fast the budget is burning, each confirmed by a
shorter window so an alert clears soon after the problem does:

| Alert | Severity | Fires when the budget burns at |
|---|---|---|
| `<Objective>BudgetFastBurn` | page | 14.4x over 1h and 5m, or 6x over 6h and 30m |
| `<Objective>BudgetSlowBurn` | ticket | 3x over 1d and 2h, or 1x over 3d and 6h |

At 14.4x, a month's budget is gone in about two days; at 1x, exactly at the end of the window.

## Alerts

Every alert lives in `ops/alerts`: the generated burn-rate rules, and
[`app.rules.yml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/ops/alerts/app.rules.yml)
for the two that are not burn rates. `InstanceDown` fires when the API has not answered a scrape for
5 minutes (nothing else can be measured then), and `Neo4jUnreachable` when `sdlc_dependency_up` for
Neo4j has been 0 for 5 minutes. The rules assume Prometheus scrapes the API as job
`sdlc-graph-backend`.

Every alert carries a `runbook_url` to its page under [runbooks](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/tree/main/docs/runbooks). An alert and its
runbook cannot drift apart: `AlertRunbookTest` fails the build if an alert names a runbook that does
not exist, or a runbook is named by no alert. And every alert has promtool cases in
`ops/alerts/tests` showing it firing and not firing ([TESTING.md](TESTING.md#alert-rules)).

To add an objective: declare it in `ops/slo.yaml`, run `node scripts/slo-rules.mjs`, write
`docs/runbooks/<runbook>.md`, and add cases to `ops/alerts/tests/slo.test.yml`. To add another alert:
write it in `app.rules.yml` with a `runbook_url`, write the runbook, and add its cases to
`ops/alerts/tests/app.test.yml`. `node scripts/promtool.mjs` runs the cases.
Every new alert also needs a case in `ops/alertmanager/tests/routes.test.yml` saying which receiver it
reaches ([Routing](#routing)).

## Routing

Alertmanager delivers what Prometheus fires, configured by
[`ops/alertmanager/alertmanager.yml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/ops/alertmanager/alertmanager.yml).
It routes on the `severity` label: `page` goes to the `page` receiver and is repeated hourly while it
fires; everything else, `ticket` and any alert that forgot its severity, goes to `ticket` and is
repeated every 4 hours. Both receivers post to the same webhook today; they are kept apart so a pager
can be attached to `page` alone.

Both run from one image, `ops/monitoring`, with the rules and routes of the commit it was built
from. `docker compose --profile monitoring up -d --build --wait` runs it locally against the API,
with a stand-in receiver (`alert-sink`) that logs every delivery. On the dogfood instance it is a
private machine ([fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md#alerts)).
`deploy-check`, inside the container, proves the whole path: Prometheus scrapes the API and has the
rules, and a synthetic alert is accepted by the webhook (DEPLOYMENT.md D10).

The webhook URL is a secret and is not in the repository. Each receiver reads it from
`/etc/alertmanager/webhook-url`, which the runtime writes from `$ALERTMANAGER_WEBHOOK_URL`.

`InstanceDown` inhibits every alert with an `slo` label from the same job: a down instance burns each
error budget at once, and one page for the cause is more use than three for its symptoms.

`node scripts/promtool.mjs` checks the config with `amtool` and resolves every case in
`ops/alertmanager/tests/routes.test.yml` to the receiver it names. amtool cannot evaluate an inhibit
rule or tell where a URL came from, so `scripts/alertmanager-config.test.mjs` checks those. Changing
any of this is covered by the `observability-change` skill.

## Dashboards

The sync dashboard, "SDLC sync", is
[`ops/grafana/dashboards/sdlc-sync-dashboard.json`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/ops/grafana/dashboards/sdlc-sync-dashboard.json)
(#29, FR9; the issue asked for `deploy/grafana/`, but this repository keeps its operational
configuration under `ops/`). It shows connector runs per hour by status, the p95 run duration over
the past hour, freshness by connector, errors per hour by kind, and the graph's nodes and edges by
type.

`docker compose --profile monitoring up -d --build --wait` runs Grafana
(`grafana/grafana:13.2.3`) beside Prometheus, at `http://localhost:3000`. It provisions a Prometheus
datasource on the `monitoring` service and every dashboard under `ops/grafana/dashboards`, from
[`ops/grafana/provisioning`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/tree/main/ops/grafana/provisioning).
Anyone who can reach the port sees the dashboards as a viewer without signing in, which suits a
laptop and nothing else. Provisioned dashboards cannot be saved from the UI: change the JSON, which
is the only copy that survives a restart. The same profile runs one Prometheus for both the alerts
and the dashboards rather than a second one for Grafana, as the issue's `observability` profile
would have.

Grafana is not deployed to the dogfood instance, to keep it within fly's free tier: its monitoring
machine is already the one that exists only to watch the others, and Grafana would be another
always-on machine with a volume for its database, which the cost the fly README sets out does not
include. To look at the dogfood instance's metrics,
proxy its Prometheus ([fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md#alerts))
and query it there.

`node scripts/dashboard.mjs` holds the dashboard to this document: every metric a panel's query
reads must be in the [Metrics](#metrics) table (a histogram's `_bucket`, `_count` and `_sum` series
count as the histogram), every panel must query the provisioned datasource, and every query must
parse, which promtool checks by loading each one as a recording rule. So a meter renamed or removed
here fails the check until the dashboard follows, and a panel cannot read a metric nobody documented.
It runs in CI (the Guards job) and on `pre-commit` when the dashboard or this document changes. The
CI end-to-end job starts Grafana and checks that it has provisioned the dashboard and a healthy
datasource, and that the API's `sdlc_graph_nodes` reaches it through Prometheus.
