# Availability error budget burning

## What fired

`AvailabilityBudgetFastBurn` (page) or `AvailabilityBudgetSlowBurn` (ticket). More of the API's
requests are failing with a server error than the objective allows: 99.5% of requests outside
`/actuator` answered without a 5xx over 30 days (`ops/slo.yaml`). The page fires when the budget would
be gone within days at the current rate, the ticket when it would not last the month.

## What it means for users

Some share of reads and writes in the web interface or the API is failing with an error.

## Check

- Which requests fail, and since when:
  `sum by (uri, status) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))`.
- Whether the store is behind it: `sum by (operation) (rate(sdlc_graph_store_errors_total[5m]))` and the
  `graph.store.failed` events in the logs (`flyctl logs --app sdlc-graph-backend`), each with the
  `requestId` of the request that failed.
- Whether it started with a deploy: `sdlc_build_info` shows the commit each instance runs, and
  `/actuator/info` the same.

## Fix

- **Neo4j is failing**: see [neo4j-unreachable.md](neo4j-unreachable.md).
- **A deploy broke an endpoint**: revert the commit on `main`. The deploy follows green CI on `main`,
  so the revert is what gets the old behaviour back.
- **One request shape fails**: find it by its `requestId` in the logs; the `stack_trace` field has
  the cause.

## Afterwards

If the page fired, open an issue with the cause and the time the budget spent, and link it from the
fix.
