# ADR-0015: Merging two nodes retires one with a pointer to the other

## Status

Accepted. Implemented by [#98](../../../issues/98).

## Context

Derived identity stops most duplicates from being created ([ADR-0003](0003-hybrid-ontology-registry.md),
[ADR-0013](0013-provider-id-is-an-alias-not-the-key.md)). A real estate still produces some:

- a repository written by hand under one remote and by a connector under another before either knew
  its provider id;
- an artifact first seen as `<name>:<version>` because the report that named it had no digest, and
  later seen with one;
- an environment keyed by a spelling the alias table did not yet fold.

Each is two nodes for one thing, with the thing's edges split between them. A rename (#88) cannot
help, because both keys are held. So two nodes have to become one, which raises three questions:

1. What happens to the node that goes away?
2. When the two disagree, whose values win?
3. What happens when something writes the old key again?

The answers had to hold for a merge a person asks for through the API, and for the one the graph
makes on its own when a digest shows two artifacts are one.

There were three options for the node that goes away:

1. **Delete it.** This is the simplest, but it destroys the audit trail: nothing records that the
   node existed, who merged it, or what it held. The next sync that still reports the old key
   re-creates the duplicate the merge removed. Something has to remember the old key, and a deleted
   node cannot.
2. **Keep it as a tombstone, a node of another label.** This keeps the record, but the old key then
   has two meanings, one per label, and every query that resolves a key would have to know about it.
3. **Retire it where it is, with a pointer.** It keeps its label and key. It is closed like any other
   retired fact (#33), with the reason `merged`, and records which node it went into.

## Decision

- **The source is retired with a pointer, never deleted.**
  - `validTo` is set to the time of the merge, unless the node was retired earlier.
  - `retiredReason` becomes `merged`, a new value of the #33 enum.
  - Two hidden provenance properties record the target's key (`mergedInto`) and who merged it
    (`mergedBy`).
  - The acting principal is written onto its provenance (FR-5).
  - Its history is kept, and `GET /api/v1/lifecycle/history` shows where it went and who sent it
    there.
- **Every edge moves in one transaction.** For each edge type, in both directions:
  - an edge between the two nodes is dropped;
  - an edge the target already has, to the same other end, is collapsed into the target's own;
  - every other edge is re-created on the target with the same properties and provenance.

  The whole merge runs in one explicit driver transaction, so a failure part-way leaves the graph as
  it was. A dry run is the same transaction rolled back, so a preview is exactly what the merge would
  do.

  Edges to the graph's own records stay with the source, as retiring leaves them. An example is the
  PRODUCED edge from the sync run that wrote it.
- **The target wins.**
  - It keeps every value it holds.
  - It takes from the source only the non-identity values it lacks.
  - It re-derives what the server derives, such as an Artifact's `identityQuality`.
  - It never takes a value that would move its own key.

  The response lists what the target gained and which disagreements it kept, so nothing is decided
  silently.
- **Some disagreements are refused, not won.** The registry declares a `mergeScope` per type: the
  properties two nodes must not disagree on to be one thing. A repository on `github.com` is never the
  one on `gitlab.com`. A merge is refused with `409 identity conflict` where both nodes hold a value
  in the merge scope, or in the alias, and the values differ. The refusal names every such field with
  both values.
- **The old key keeps resolving.**
  - The source's key is added to the target's `previousKeys`, with every key the source had.
  - A read of the old key answers with the target, including a read as of a later instant.
  - A create of it is refused, naming the target.
  - An update addressed at it is refused with `409 node already merged`.
  - A connector writing it is absorbed: a node write to a merged key changes nothing, and an edge to
    one lands on the target. So a sync still reporting the old key neither fails nor re-creates the
    duplicate.
  - A later merge of the target points every earlier merge at the new target, so the chain stays one
    hop long.
- **Merging is privileged.**
  - The route needs `graph:admin` as well as `graph:write`, like the lifecycle's administrative
    routes, and a read-only deployment refuses it.
  - A merge into itself, across types, of the graph's own records, of a node already merged, or into a
    retired node is refused before anything is written.
- **An artifact's digest folds its version-only twin automatically.** When a writer stores an
  Artifact with a digest, the graph looks for the current node keyed `<name>:<version>`. It folds that
  node into the digest node only when all of these hold:
  - the digest node is the only current one with that name and version;
  - the two differ in nothing but the digest.

  The fold is recorded as the write that caused it. It is deterministic, and it is idempotent: a node
  already folded is retired, and is left alone.

## Consequences

- A merge cannot be undone by another write. That is why it is privileged, and why it can be
  previewed.
- A retired source stays in the graph, and in counts of retired nodes, until the archive purges it
  like any other retired fact (#33). Once it has been purged, its key resolves only through the
  target's `previousKeys`.
- A write to a merged key is absorbed silently rather than refused, so a connector keeps working. The
  API refuses such a write, so a person is told.
- Changing the environment alias table does not move Environment nodes already keyed by an old
  spelling. Merging them is the way to fold them.
- A change of `mergeScope` needs no migration: it constrains merges, not stored data.
