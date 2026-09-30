<!-- GENERATED FROM docs/adr/0016-context-pack-templates-are-registry-data.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0016: Context pack templates are registry data, walked within a budget

## Status

Accepted. Implemented by [#96](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/96).

## Context

A coding agent asked to change a repository, triage an incident or find who reads a data store
needs a few dozen facts from the graph, not hundreds of files. `POST /api/v1/impact`
([ADR-0011](/adr/0011-impact-scoring-versioned-and-deterministic)) answers one of those questions, for
one start type, along every propagating edge. The other tasks walk different edges from different
starts: an incident on a database goes to the repository that owns it, then to its pipelines, its
service record and the APIs it calls, and not to the libraries it uses.

An open traversal (`startId`, `edges`, `depth`) would answer all of them and bound none: the caller
would choose how much of the graph to read, and every agent would have to know the ontology well
enough to choose well. A traversal per task written in Kotlin would bound them, at the price of a
release for every new task and a query that could name an edge the registry no longer has.

## Decision

- **A template is data in the registry.** `templates.yaml` names each template, the node types it
  starts from, whether nodes bring their owners, and its steps: an edge by its own name or its
  inverse, an optional property filter (`where`), a repeat (`min`..`max`, at most 5) and `then`, the
  steps walked from what it reaches. The application refuses to start, and `ontologyLint` fails
  (ONT014), on a template that names an edge that does not exist, walks one from a type it cannot
  stand on, filters on a property or enum value the edge does not declare, or repeats out of bounds.
  The templates are published at `GET /api/v1/ontology` and in the generated TypeScript. Three ship:
  `change-impact`, `incident-triage` and `data-consumers`.
- **The caller names a template and a budget, never a walk.** `budget` is required, 1 to 500, and
  counts the nodes returned beside the start. The walk is one store step per repeat of each template
  step, and a trail never revisits a node on it, so a cycle ends a trail rather than the walk.
- **The ranking is #87's, extended.** Each node is explained by its nearest path
  (`ImpactScorer.nearest`), and the pack is ordered by distance ascending, path confidence
  descending, the #87 impact score (scoring version 1, the node placed in its environment as
  `POST /api/v1/impact` places it) descending, and id ascending: a total order made in the
  application, so the same graph gives the same pack. Distance leads rather than score because a
  pack is read as context from the start outwards; the score orders what is equally near, which is
  where a production deployment should come before a staging one.
- **The cut is counted.** Unlike ADR-0011's uncounted truncation, a pack reports `reached`, `cut`
  and `truncated`. Counting costs nothing here: the template has already bounded the walk and every
  node reached has been ranked to choose the budget's share. An agent that sees `cut: 25` knows to
  ask again with a larger budget or a narrower template. `truncated` is also true when a store step
  hit its row cap.
- **`current` is the latest success per environment.** A step marked `current` (only one that
  reaches Deployment alone) keeps, of the deployments reached from each repository, the latest
  `SUCCESS` by `deployedAt` in each environment, as of `asOf` when one is given. A failed deployment
  replaced nothing.
- **Owners are one hop of the registry's owner edges.** A template with `owners: true` adds the
  teams the `ownership: owner` edges of the start and of every node reached name, each one hop
  past the path to what it owns, counted against the budget like any other node. Inherited
  ownership (#21's `OwnerResolver`) is not walked: a pack shows the owner edge the graph holds, and
  the edge it rests on, rather than an inference.
- **Every fact says where it came from, in one line.** Each node and edge carries
  `{source, observedAt, confidence, inferred, stale}`, `stale` judged now; each edge carries the
  `manifest`, `rule` or `commitSha` it rests on where it has one. Node ids and edge ids
  (`TYPE:from>to`) are the graph's own, stable across requests, so a renderer (#79) can cite them.

## Consequences

- A new kind of task is a registry change and a restart, reviewed like any other registry change,
  not a new endpoint. A template cannot drift from the edges it walks, because both are validated
  together at startup and in the build.
- The walk is bounded by the template's shape, not by a node count: a template repeated five times
  over a dense dependency graph can reach many nodes before the budget ranks them. Each store step
  reads at most 10,000 rows and a walk records at most 20,000 paths, beyond which the pack says it
  is truncated.
- `DEPENDS_ON` may now end at a CloudResource (`kind: data`), which `data-consumers` walks backwards;
  that widening is ontology 1.6.0, a minor bump with no migration.
- No policy filtering is applied yet (#30): a pack holds what the caller's `graph:read` can read,
  which is everything, as every other read does today.
