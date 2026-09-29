# Neo4j unreachable

## What fired

`Neo4jUnreachable` (page). The API is up but its Neo4j health check has not been UP for 5 minutes
(`sdlc_dependency_up{dependency="neo4j"} == 0`).

## What it means for users

Every graph read and write fails. The web interface loads, but shows errors where the graph should
be.

## Check

- Whether Neo4j is running: `flyctl status --app sdlc-graph-neo4j` and
  `flyctl logs --app sdlc-graph-neo4j`.
- Whether its volume is full: `flyctl volumes list --app sdlc-graph-neo4j`.
- What the API sees: `graph.store.failed` events in `flyctl logs --app sdlc-graph-backend`, with the
  operation and the driver's exception.

## Fix

- **Neo4j stopped**: `flyctl machines restart <id> --app sdlc-graph-neo4j`.
- **The volume is full**: extend it with `flyctl volumes extend <id> --size <gb> --app sdlc-graph-neo4j`.
- **Credentials changed**: `NEO4J_PASSWORD` must match on both apps; set it with `flyctl secrets set`
  on each and restart.

## Afterwards

Nothing is lost while the API cannot write: the next scheduled sync and the dogfood seed restate
what is missing. Check that the next seed run succeeded.
