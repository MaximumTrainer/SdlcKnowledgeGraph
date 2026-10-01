# ADR-0018: CI deployments are read from GitHub Actions by the connector the pipeline reports to

## Status

Accepted. Implemented by [#90](../../../issues/90).

## Context

[#7](../../../issues/7) lets a deploy pipeline report what it deployed, through the `github-actions`
connector, behind the ingest token. A pipeline that does not report leaves nothing behind, though,
and most of an estate's pipelines will never be changed to report. [#90](../../../issues/90) asks for
the same facts read from GitHub instead: the artifacts each workflow run published (FR-1), the
deployments it made, with their environment and status (FR-2, FR-3), the deployment a newer one
replaced, closed (FR-4), a deployment in the graph within a minute of GitHub saying so (FR-5), and
every fact attributed to the run that produced it (FR-6). Whatever it writes has to land on the same
nodes the deployment ingest and the dogfood seed already write.

Six things needed deciding: where the connector lives, how it tells GitHub's events from a pipeline's
report, how it knows which run published a package, how a deployment is closed, what an `inactive`
status means, and which time a deployment is keyed by.

## Decision

- **The `github-actions` connector reads GitHub, not a second connector.** `sourceSystem=github-actions`
  already names what GitHub Actions did, the deployment ingest writes under it, and a service
  principal writing its facts through the API already holds `graph:write:github-actions`. A second
  connector would have split one source's facts across two names, so that reconciliation, freshness
  and the run history each told half the story. The connector keeps taking reports and now also
  polls (`INCREMENTAL`, `FULL`) and takes GitHub's `workflow_run` and `deployment_status` events
  (`WEBHOOK`). It reads with the GitHub connector's configuration (`connectors.github.orgs`,
  `connectors.github.token`) and HTTP client, so both read the same owners with one credential, an
  owner that is a user account is read as one by both, and the token is handled and kept out of the
  logs in one place. Its version is 2.0.0.

- **A request signed by GitHub is judged by its signature alone.** A request carrying
  `X-Hub-Signature-256` is verified by HMAC with `connectors.settings.github-actions.webhook-secret`
  and is then a GitHub event; anything else is a deployment report, believed only with the bearer
  ingest token. A signed request with a bad signature is refused even when it also carries the right
  token, so the ingest token can never carry an event GitHub did not sign. The secret is this
  connector's own rather than the GitHub connector's, since the two are separate webhooks in GitHub.

- **A package version belongs to the run of its repository that was running when it was published.**
  GitHub records no link from a package version to the run that pushed it. What it does record is the
  repository a package is published from and when each version was created, so a version is credited
  to the one run of that repository whose `[run_started_at, updated_at]` covers its creation. A
  version two runs could have published is credited to neither and logged
  (`actions.package.ambiguous`), because guessing would record a commit the artifact may not have
  been built from. A container image is keyed by digest at full confidence; an npm or Maven package
  has no digest, so it is keyed `<name>:<version>` at confidence 0.8 and folds into its digest's node
  when a writer reports one ([#98](../../../issues/98)). A deployment whose statuses name no run, and
  whose commit no run published anything for, deployed nothing the connector can name, so it is
  logged (`actions.deployment.unattributed`) and not recorded.

- **A successful deployment closes the one it replaced, as `superseded`.** Per artifact family (the
  registry and name, whatever the build) and environment, a successful deployment ends the previous
  successful one at the moment it was deployed. That is a new retirement reason, `superseded`
  (ontology 1.8.0), applied through the same `FactLifecycle.retire` as a tombstone, so the closed
  Deployment keeps its history and stops being current. To make `validTo` that moment rather than
  when the connector noticed, `NodeUpsert` and `EdgeUpsert` gained `validFrom`: a Deployment and its
  `DEPLOYED_TO` and `TO_ENVIRONMENT` edges begin when they were deployed, never later than now. A
  failed deployment replaces nothing and is replaced by nothing. Only deployments this read wrote, or
  their successors, are closed, so reading the same history twice closes nothing twice. The
  deployment ingest does not supersede: a report says what one deploy did, not what it replaced.

- **`inactive` is GitHub marking a deployment replaced, not how it went.** GitHub sets `inactive` on
  an older deployment when a newer one to the same environment succeeds. Read as the latest status it
  would turn a successful deployment into something else and, worse, make the connector rewrite a
  deployment it had just superseded as current. So the latest status is the newest one that is not
  `inactive`, and a `deployment_status` event saying `inactive` is ignored.

- **A deployment is keyed by when GitHub created it.** The key is `<artifactKey>#<environmentKey>#<epoch
  seconds>`, as the ingest's is. The connector uses the deployment's `created_at`, which never changes,
  so a webhook and a later poll of the same deployment write one node. A pipeline's report names its
  own `deployedAt`, which is usually a little later, so the ingest's Deployment and the connector's
  for the same deploy are two nodes; the Artifact, Environment (aliases resolved through the
  registry, so `prod` is `production`) and Pipeline they hang off are the same ones. Every fact's
  `sourceId` is the run id and its `observedAt` the run's completion.

## Consequences

- An organisation gets its deployments in the graph without changing a pipeline, from the token the
  GitHub connector already has, with `actions:read`, `deployments:read` and `packages:read` added.
- A poll reads a window, not everything (`connectors.github.deployments.lookback`, seven days by
  default, from the watermark), so a full run is not a complete statement of the source and retires
  nothing it did not see. A run that GitHub created before the window and completed inside it is
  caught by listing runs from `max-run-duration` (six hours) earlier.
- Packages are listed once per owner and kind per run, then filtered by repository, so the cost is per
  owner rather than per repository.
- Where both a report and the connector see a deploy there are two Deployment nodes for it. Merging
  them would need the report to name the GitHub deployment it was, which a report sent from a
  workflow step does not know; until it does, the artifact's `DEPLOYED_TO` edges show both.
- Cloud-side confirmation that what was deployed is running ([#91](../../../issues/91)) and other CI
  providers are not part of this.
