<!-- GENERATED FROM docs/OBSERVABILITY.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Observability

What the application says about itself, and how to follow one request through it. This is being
built in parts under [#44](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/44): request correlation and the log format are here; the
declared event registry, metrics, service objectives, alerts and runbooks come next.

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
