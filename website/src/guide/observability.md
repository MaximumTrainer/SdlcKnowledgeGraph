<!-- GENERATED FROM docs/OBSERVABILITY.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Observability

What the application says about itself, and how to follow one request through it. This is being
built in parts under [#44](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/44): request correlation, the log format and the declared
event registry are here; metrics, service objectives, alerts and runbooks come next.

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
