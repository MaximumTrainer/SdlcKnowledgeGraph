# ADR-0014: The data lifecycle: versions, retirement, archival and ontology migrations

## Status

Accepted. Implemented by [#33](../../../issues/33).

## Context

A fact in the graph is true over an interval, `[validFrom, validTo)`. Before this change the graph
could answer *whether* a fact held at an instant:

- `asOf` reads, the stale flag and keeping `validFrom` across rewrites ([#93](../../../issues/93));
- tombstones that set `validTo` ([#22](../../../issues/22));
- reconciliation, which closes what a successful complete full sync stopped reporting
  ([#150](../../../issues/150)).

It could not answer *what* a fact said at that instant. A write overwrote the node's values, so an
`asOf` read of a node renamed last week returned this week's name. Four other things were missing:

- a way to tune reconciliation per connector;
- any way to get closed facts out of the graph;
- any way to change the shape of data already stored when the registry renames or removes something.
  [ONTOLOGY.md](../ONTOLOGY.md#versioning) said so plainly: "today that means not renaming or
  removing";
- a place to see and operate the above.

Two of these can destroy data. An archive job that deletes, and a migration that rewrites every node
of a type, are the most dangerous code this system runs. Most of this decision is about keeping them
from doing that by accident.

## Decision

### Versions are nodes beside the node, cut only on change

- **The node stays the current view.** A write that changes a node's registry properties first copies
  the values it replaces, with their provenance, into a `NodeVersion`. That is a meta node with
  `versionOf` (the node's id), `since` and `until`. It is cut in the same Cypher statement as the
  write, so a version and its change cannot be separated.
- **What does not cut a version.** A write restating the same values, or changing provenance alone,
  cuts none: a connector that re-reads the estate every hour would otherwise add a version per node
  per hour. Reopening a closed node does cut one.
- **Reads.** Current reads are unchanged, and so is every response shape. An `asOf` read before the
  node's `prov_propsFrom` answers from the version that held then.
  `GET /api/v1/lifecycle/history` lists the versions.
- **Linking.** A version is linked to its node by the `versionOf` property rather than a `HAS_VERSION`
  edge. An edge would appear in every traversal, neighbourhood and blast radius unless each of them
  learnt to skip it. A property keeps versions out of all of them by construction.
- **Limits.** A node keeps its newest `max-versions` (50). Versioning can be turned off, or skipped for
  named types. Meta types are never versioned.

### Retirement records why, and connectors choose their rules

- **Reasons.** A closed node records `prov_retiredReason`: `source-deleted`, `source-retired`,
  `missing-from-sync` or `manual`.
- **Edges.** A tombstone or reconciliation also closes the node's current edges. Neither ever closes a
  validity before it began.
- **Per-connector rules.** Each connector sets `lifecycle.missing-from-full-sync` (`tombstone` or
  `ignore`) and `lifecycle.grace-period` under `connectors.settings.<name>`.
- **No switch for partial runs.** There is deliberately no setting that lets a partial or failed run
  retire anything. #150's rule that only a successful complete full sync may retire stays
  unconditional, and the status API reports it as `requireSuccessfulRun: true` so nobody goes looking
  for the switch.

### Archival is off, then a rehearsal, then an explicit choice to delete

Each step towards deleting data is a separate setting that someone has to write down:

1. **`LIFECYCLE_ARCHIVE_ENABLED`, default `false`.** While it is false no job is scheduled; the
   scheduler bean does not even exist. The endpoint only rehearses (`dryRun=true`); asked to run for
   real, it answers `409` naming the setting.
2. **`LIFECYCLE_ARCHIVE_MODE`, default `dry-run`.** Enabled, the job still only counts what it would
   take and logs it.
3. **`export`.** Writes the eligible facts to a JSONL file and changes nothing in the graph.
4. **`purge`.** Writes the file, closes it, and only then deletes what it wrote. Any failure writing
   the file deletes nothing.

A fact is eligible when it is a domain node closed more than `retention` (P365D) ago, or a
relationship that ended then or touches such a node. Meta types are never archived. A run that
writes is recorded as a sync run of `lifecycle-archive`.

The dogfood instance (`fly/fly.backend.toml`) and the compose stack set none of these variables. So
neither ever archives or deletes a fact until someone opts in:

```bash
LIFECYCLE_ARCHIVE_ENABLED=true                                # scheduled; still only counts
LIFECYCLE_ARCHIVE_ENABLED=true LIFECYCLE_ARCHIVE_MODE=export  # also writes the archive file
LIFECYCLE_ARCHIVE_ENABLED=true LIFECYCLE_ARCHIVE_MODE=purge   # writes it, then deletes
```

On Fly these are `fly secrets set` or `[env]` entries, and `LIFECYCLE_ARCHIVE_DIRECTORY` must be on a
volume. [OBSERVABILITY.md](../OBSERVABILITY.md#archival) has the rest.

### Migrations are versioned files, applied at startup, one transaction each

**Files.** A migration is a file under `ontology/migrations/` named for the registry version it
reaches: `V1_5_0__rename_ci_legacy_name.yaml`, or `.cypher`.

- **YAML.** The declarative form offers `renameProperty`, `addProperty`, `dropProperty`, `renameType`,
  `mergeTypes` and `renameEdge`. Each compiles to an idempotent statement and is checked against the
  registry it migrates to. A name that is not a plain identifier is refused, since labels and property
  names cannot be Cypher parameters.
- **Cypher.** The raw form is for anything else.

**At startup,** after the schema initialiser:

- **A newer graph.** A graph whose `Ontology` version is newer than the build is refused, as since
  [#85](../../../issues/85).
- **A new graph.** A graph with no recorded version is baselined: every shipped migration is recorded
  without running, because it never held the old shape.
- **An older graph.** Each pending migration runs in version order. Each runs in one transaction
  together with its `OntologyMigration` record (id, version, SHA-256 checksum, applied at, duration).
- **A failure.** A failing migration rolls back its own transaction, logs `ontology.migration.failed`
  and stops startup. The migrations before it stay applied and recorded, so the next start resumes
  after them.
- **Refusals.** Startup is also refused for a file whose checksum no longer matches its record, for
  two files for one version, or for a file newer than the registry.
- **The recorded version.** It moves to the build's only once nothing is pending.

**Manual mode.** `LIFECYCLE_MIGRATIONS_MODE=manual` applies nothing at startup. It logs
`ontology.migrations.pending`, and an administrator applies the migrations through
`POST /api/v1/lifecycle/migrations/apply`.

**Dry run.** `./gradlew ontologyMigrateDryRun -Pfrom=<version>` prints what would run, without a
database.

**Nothing shipped.** No migration ships with this change. The registry moves to 1.4.0 only by adding
the two meta types, so there was no genuine migration to write, and inventing one to exercise the
framework would rewrite real data for nothing. The framework is proven by test fixtures instead,
including a failing one.

### Administration needs a new scope

- **`graph:admin`.** Applying migrations and running the archive change the graph as a whole, so they
  need `graph:admin` together with `graph:write`. The development realm gives it to `dan` through
  the realm role `graph-admin`.
- **Reads.** Reading the status and a node's history needs `graph:read`.
- **Read-only and anonymous.** Both `POST`s are refused by the read-only posture, and therefore always
  in the anonymous mode.
- **Health.** Nothing here changes health. A pending migration in manual mode, a disabled archive, or
  a failed scheduled archive run leaves `/actuator/health`, readiness and liveness as they were.

## Consequences

- **`asOf` reads.** An `asOf` node read now answers with the values of the time, not only whether the
  node existed. List, neighbourhood, traversal and impact reads take no `asOf` and have no
  `includeRetired` filter; they still read the current graph, closed facts included, as before.
- **Registry version.** The registry is 1.4.0, with the meta types `NodeVersion` and
  `OntologyMigration`. Neither can be written through `/api/v1/nodes` (`403 managed node type`). A
  1.3.0 build refuses to start against a graph a 1.4.0 build has recorded.
- **A `PUT` that closes a node** records `manual` but leaves the node's edges open, as before. Only
  retirement by a source closes edges.
- **Purge in one transaction.** A purge deletes in one transaction, not in batches. Anything retired
  within the retention is untouched, and the archive only ever sees what ended long ago. An estate
  large enough for that to outgrow the heap should run `export` first and purge in smaller retention
  steps.
- **Not built.** These parts of #33's wider sketch are not built:
  - an automatic detector that says a registry change needs a migration;
  - an S3 archive target, or a mode that relabels instead of deleting;
  - a per-label index on `prov_validTo`. The archive query is label-less and runs rarely.
- **Rolling back past a migration** means restoring the graph from before it, as for any version
  bump.
