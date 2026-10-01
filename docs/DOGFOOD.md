# The dogfood instance

`https://sdlc-graph.fly.dev` is this project's own public instance. It runs `main`, it holds this
repository's own SDLC, and it is the reference implementation of the [deployment contract](DEPLOYMENT.md):
the conformance suite runs against it after every deploy, and a deploy that fails it is red.

Why fly.io, and why the graph is disposable, is [ADR-0010](adr/0010-dogfood-on-fly-io.md). How it
is set up and operated is [fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md).

## What it holds

| Facts | Written by | Provenance `sourceSystem` |
| --- | --- | --- |
| This repository, a team per `CODEOWNERS` owner, a pipeline per workflow with its last run, and the repositories it depends on by git remote | The daily seed, `scripts/dogfood-seed.mjs` ([Adapters](ADAPTERS.md#seeding-this-repository-on-the-dogfood-instance)) | `dogfood-seed` |
| Each deployment of the instance itself: which images, from which commit, and whether it worked | The deploy, `scripts/deployment-report.mjs` ([Adapters](ADAPTERS.md#self-ingestion-deployments-from-the-pipeline)) | `github-actions` |

The seed stands in for the GitHub connector ([#23](../../issues/23), [#86](../../issues/86)), which
now reads everything the seed does and is off here until it is given an owner and a token
([Adapters](ADAPTERS.md#running-the-github-connector-on-the-dogfood-instance)); its facts carry
`sourceSystem=github`. The seed and the deploy report need the `INGEST_TOKEN` secret on the `dogfood`
environment; without it, neither writes and both say so.

The github-actions connector ([#90](../../issues/90)), which reads deployments from GitHub rather than
from the deploy's report, is off here as well. Turned on, it would find each deploy's GitHub
deployment to `dogfood` but no artifact for it, since the images go to fly.io's registry rather than
GitHub Packages, so the deploy report stays this instance's record of its deploys
([Adapters](ADAPTERS.md#running-the-github-actions-connector-on-the-dogfood-instance)).

## It watches itself

A private monitoring machine runs Prometheus and Alertmanager with the rules in `ops/alerts`, built
into its image from the deployed commit ([OBSERVABILITY.md](OBSERVABILITY.md#alerts)). It alerts on
error budget burn, a down API and an unreachable database, and posts to the webhook in the
`ALERTMANAGER_WEBHOOK_URL` secret. Every deploy proves an alert gets through (D10).

## It is read-only

The instance has no identity provider, so it runs the API's anonymous read-only mode
([AUTH.md](AUTH.md#without-an-identity-provider-the-anonymous-read-only-mode), #118): anyone may read,
nobody signs in, and nothing can be written by nobody. It runs with `sdlc.read-only=true` and no
`AUTH_ISSUER_URI`, and the API would refuse to start without the first (D4, D13). Browsing, the REST
reads and GraphQL queries work without a token; the web interface offers no way to change anything,
and every other request is refused with `403 {"error": "this instance is read-only"}`. The two
exceptions are the ingest endpoints above, which the pipeline and the seed use, each behind the
ingest token (D6). The API logs `auth.anonymous.readonly` on every start to say so.

Nothing on it is archived or deleted by the data lifecycle either ([#33](../../issues/33)): the
archive job is off unless `LIFECYCLE_ARCHIVE_ENABLED` is set, and `fly/fly.backend.toml` does not set
it. The lifecycle's admin endpoints are writes, so the read-only posture refuses them; the Lifecycle
page reads its status and offers no controls. Ontology migrations still run at startup, since they
keep the graph readable by the build that was deployed ([ADR-0014](adr/0014-data-lifecycle.md)).

## Its data is re-derived, not backed up

Every fact carries provenance and can be written again by re-running what wrote it. So the database
has no backup: if it is corrupt, the recovery is to destroy the volume, let the next deploy create an
empty one, and run the Dogfood seed workflow ([fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md#recovery-destroy-and-re-derive)).
The deployment history starts again from that deploy, which is the one thing a rebuild loses.
