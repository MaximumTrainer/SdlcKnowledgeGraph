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

`type` is always a type the ontology declares, because an undeclared one is refused before anything
is counted, so the label's cardinality is the size of the ontology. A refusal the store is designed
to give, such as an edge whose end does not exist, is an answer rather than a failure and is not
counted as a store error.

Writes that arrive through a connector or an ingest endpoint are not counted here; connector metrics
are [#29](../../issues/29).

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
