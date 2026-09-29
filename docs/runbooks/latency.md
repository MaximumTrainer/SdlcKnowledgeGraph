# Latency error budget burning

## What fired

`LatencyBudgetFastBurn` (page) or `LatencyBudgetSlowBurn` (ticket). More of the API's requests are
taking longer than 500 ms than the objective allows: 99% of requests outside `/actuator` within
500 ms over 30 days (`ops/slo.yaml`).

## What it means for users

The web interface is slow to load pages and search results; nothing is failing yet.

## Check

- Which requests are slow:
  `histogram_quantile(0.99, sum by (uri, le) (rate(http_server_requests_seconds_bucket[5m])))`.
- Whether Neo4j is the slow part: the same query for `/api/v1/graph` and neighbourhood reads, which
  do the most traversal, and the Neo4j machine's load (`flyctl status --app sdlc-graph-neo4j`).
- Whether the API is short of memory: `jvm_memory_used_bytes` against `jvm_memory_max_bytes`, and
  `jvm_gc_pause_seconds`.

## Fix

- **A traversal got expensive**: a large neighbourhood or an unbounded query; bound it and ship the
  fix through `main`.
- **The machine is too small**: the dogfood instance runs on the smallest fly.io machines; scale
  memory in `fly/fly.backend.toml` or `fly/fly.neo4j.toml`.
- **Garbage collection**: raise the heap share in `JAVA_TOOL_OPTIONS` in `fly/fly.backend.toml`.

## Afterwards

Record which endpoint was slow and why in the issue for the fix.
