<!-- GENERATED FROM docs/DOGFOOD.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# The dogfood instance

`https://sdlc-graph.fly.dev` is this project's own public instance. It runs `main`, it holds this
repository's own SDLC, and it is the reference implementation of the [deployment contract](/guide/deployment):
the conformance suite runs against it after every deploy, and a deploy that fails it is red.

Why fly.io, and why the graph is disposable, is [ADR-0010](/adr/0010-dogfood-on-fly-io). How it
is set up and operated is [fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md).

## What it holds

| Facts | Written by | Provenance `sourceSystem` |
| --- | --- | --- |
| This repository, a team per `CODEOWNERS` owner, a pipeline per workflow with its last run, and the repositories it depends on by git remote | The daily seed, `scripts/dogfood-seed.mjs` ([Adapters](/guide/adapters#seeding-this-repository-on-the-dogfood-instance)) | `dogfood-seed` |
| Each deployment of the instance itself: which images, from which commit, and whether it worked | The deploy, `scripts/deployment-report.mjs` ([Adapters](/guide/adapters#self-ingestion-deployments-from-the-pipeline)) | `github-actions` |

The seed stands in for the GitHub connector ([#23](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/23)), which replaces it. Both need the
`INGEST_TOKEN` secret on the `dogfood` environment; without it, neither writes and both say so.

## It watches itself

A private monitoring machine runs Prometheus and Alertmanager with the rules in `ops/alerts`, built
into its image from the deployed commit ([Observability](/guide/observability#alerts)). It alerts on
error budget burn, a down API and an unreachable database, and posts to the webhook in the
`ALERTMANAGER_WEBHOOK_URL` secret. Every deploy proves an alert gets through (D10).

## It is read-only

There is no sign-in yet ([#3](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/3)), so an instance anyone can reach must not take writes
from anyone: it runs with `sdlc.read-only=true` (D4, D5). Browsing, the REST reads and GraphQL
queries work; every other request is refused with `403 {"error": "this instance is read-only"}`.
The two exceptions are the ingest endpoints above, which the pipeline and the seed use, each behind
the ingest token (D6). This is a stopgap until authentication lands, not an access model.

## Its data is re-derived, not backed up

Every fact carries provenance and can be written again by re-running what wrote it. So the database
has no backup: if it is corrupt, the recovery is to destroy the volume, let the next deploy create an
empty one, and run the Dogfood seed workflow ([fly/README.md](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/fly/README.md#recovery-destroy-and-re-derive)).
The deployment history starts again from that deploy, which is the one thing a rebuild loses.
