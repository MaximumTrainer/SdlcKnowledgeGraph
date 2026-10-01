<!-- GENERATED FROM docs/adr/0017-github-connector-resolves-by-what-repositories-publish.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0017: The GitHub connector resolves by what repositories publish, and counts what it changed

## Status

Accepted. Implemented by [#86](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/86).

## Context

[#23](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/23) built the GitHub connector: repositories, topics, CODEOWNERS ownership,
the dependencies seven manifest formats declare, and an index of infrastructure-as-code files.
[#86](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/86) asks for the rest of what GitHub can tell the graph in one connector: a
`Pipeline` per workflow, `DEPENDS_ON` to any repository in the graph that publishes a package a
manifest names, forks told apart, `sourceId` and `observedAt` on every fact, and a `SyncRun` that
says what it wrote, what it left unchanged and what failed, so that running it again over an estate
that did not move visibly writes nothing new.

Five things in that needed deciding, because the obvious reading of the issue disagreed with
something already built or with how the graph keeps its facts current.

## Decision

- **A dependency resolves to the repository that publishes it, whatever its name.** #23 resolved
  only names matching a configured prefix (`internal-package-prefixes`) and only against the
  repositories read in the same run. Now every declared dependency waits until every repository has
  been read, and is then matched against what this run read repositories publishing and, for
  everything else, what the graph already holds in `Repository.packageNames`
  (`PublishedPackageIndex`). What the run read wins, and the graph's memory of a repository this run
  read is ignored, since that repository has just said what it publishes now. The prefix is kept as
  a hint for webhooks only: a webhook has read one repository, so a prefixed name nothing is known
  to publish is left to the next scheduled run rather than recorded as a library. A name two
  repositories publish resolves to neither. A repository depending on a package it publishes itself
  is a workspace, not an edge. The edge is `DEPENDS_ON {kind: library, manifest, version, scope}`,
  `inferred: true` at confidence 0.9, with `sourceId` naming the manifest it was read from.

- **A dependency nothing publishes is still recorded as a `Library`.** The issue says unmatched
  dependencies are not written. That would remove what #23 shipped and documents - a runtime and a
  dev dependency on `express` read straight from a manifest, at full confidence - and with it the
  answer to "what is affected by this CVE". The issue's own concern is that no repository-to-repository
  edge is guessed from something that does not match, and none is.

- **A pipeline is keyed as the deployment reports and the seed already key one.** The issue gives
  the key as `github:<repoKey>:<workflowPath>`. A Pipeline's identity is `[provider, repoKey,
  workflowPath]` and its provider the CI system that runs it, so the connector writes provider
  `github-actions` and the key `github-actions:<repoKey>:<workflowPath>`: the same node the
  deployment ingest and the dogfood seed write for the same workflow, rather than a second one under
  a provider the registry's enum does not have. Only a file directly under `.github/workflows/` with
  a `.yml` or `.yaml` extension is one, read from the branch listing the connector already fetches,
  so a pipeline costs no request and no permission of its own. Its `lastRunStatus` is the deployment
  report's to say.

- **A fork records `forkOf` and is not a resolution target.** The org listing says a repository is a
  fork and not of what, so a fork costs one more request, reading it back for its `parent`. A fork
  carries its upstream's manifest and so claims its upstream's package names: resolving to it would
  point a dependency at a copy nobody ships, or make the name ambiguous and resolve to nothing. Its
  own dependencies are still resolved. `connectors.github.manifests.resolve-to-forks` turns the
  exclusion off.

- **A fact stated again as the graph holds it is written, and counted as unchanged.** Skipping the
  write would be the literal reading of "writes nothing new", and it would break two things: the
  freshness of a source's facts (#93) is measured from when the source last stated them, and a full
  sync's reconciliation (#150) closes what a run did not assert. So the delta writer still writes
  every fact, and compares it first with what is held: the same declared properties, the same
  source, `sourceId`, confidence and inferred flag, and still current. `observedAt` is not compared:
  a repository pushed to again reports a later time for a fact that did not move. The values are
  compared as the store hands them back (integers as Long, instants as zoned date-times). `SyncRun`
  records `written` and `unchanged` beside the existing `nodesUpserted` and `edgesUpserted`, which
  still count every fact asserted; `failed` counts each item a connector names as unreadable (a
  `PartialReadException`), each page that could not be written, and a run that failed outright as
  one; `connectorVersion` is the descriptor's `version`, which the GitHub connector moves to 2.0.0
  for this change. A run's `PRODUCED` edges are still drawn to every fact it asserted.

## Running it, and read-only instances

The connector is off unless `connectors.settings.github.enabled` is true, and does nothing without
`GITHUB_ORGS` and `GITHUB_TOKEN`: its health is then `DOWN` with the reason, and a sync fails before
it reads anything. The token comes from the environment only and is never logged; the
`config-secrets` guard keeps one out of deployment configuration. An owner in `GITHUB_ORGS` that
GitHub does not know as an organisation is read as a user account, through `/users/{owner}/repos`,
which lists the repositories the user owns and only the public ones: this project's own owner is a
user, and the alternative was a dogfood instance that could not read its own repository.

A read-only instance (`sdlc.read-only`) refuses `POST /api/v1/connectors/{name}/sync` and
`POST /api/v1/webhooks/github`, as it refuses every write through its API. A scheduled run is not a
request: like the nightly prune of old runs, it is a write the operator configured, made by the
application through the same registry-driven `GraphDeltaWriter` and provenance as any other run, and
the read-only posture does not stop it. So a read-only instance runs the connector on its schedule
exactly when its operator has set the three settings above, and never otherwise; it is not given an
ingest route of its own, because nothing outside the instance needs to reach it. The dogfood instance
sets none of them, so it is unaffected until someone does.

## Consequences

- A first sync of an organisation reads `Repository.packageNames` across the graph once, a page of
  500 at a time, at the end of the run. An incremental run resolves against repositories it skipped
  as unchanged through the same read.
- Every node and edge the connector writes costs one more read, to tell written from unchanged. A
  run over an unchanged estate reports `written: 0`, and a webhook run reports what it changed too.
- A name two repositories publish no longer resolves to whichever was read last. It is recorded as
  a library until one of them stops publishing it.
- The connector's own facts can now be told from the seed's by `sourceSystem`, and the seed can be
  retired on an instance where the connector runs; ADAPTERS.md says how.
