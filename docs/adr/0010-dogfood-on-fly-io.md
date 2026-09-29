# ADR-0010: The dogfood instance runs on fly.io

## Status

Accepted. Implemented in part by [#47](../../../issues/47). Supersedes the choice of platform in
[#6](../../../issues/6), which named Azure Container Apps as the reference, and nothing else about #6.

## Context

The graph has never held a real repository's SDLC. Both flagship questions are answered by
acceptance tests over fixtures, which shows the code works and shows nothing about whether the model
is useful. #47 asks for one real, public instance that holds this repository and is deployed from
`main`: the project's dogfood instance.

It has to be cheap enough to leave running, reachable by anyone, and it must not become an open
database, because the API has no authentication yet ([#3](../../../issues/3)).

## Decision

**fly.io**, chosen over Azure Container Apps, AWS App Runner and Google Cloud Run, because:

- it deploys the images the repository already builds with one command, and no platform-specific
  packaging;
- it gives the organisation a private IPv6 network, so the database has no public listener at all
  rather than a public one behind a firewall rule;
- machines stop when idle and start on the next request, so an instance that is mostly unvisited
  costs close to nothing.

Three apps (`fly/`):

| App | Public | Size | Notes |
| --- | --- | --- | --- |
| `<prefix>` (web) | Yes: `https://<prefix>.fly.dev` | 256 MB, stops when idle | nginx: the SPA, and `/api/` proxied to the backend |
| `<prefix>-backend` | No: `.flycast` only | 512 MB, stops when idle | Runs with `SDLC_READ_ONLY=true` |
| `<prefix>-neo4j` | No: `.internal` only | 512 MB, always on, 1 GB volume | No service declared, so fly allocates no address |

**Neo4j runs as a fly Machine with a volume**, not on Neo4j Aura, because Aura Free pauses after
inactivity, and because the dogfood graph is **disposable**. Every fact carries provenance and can
be re-derived from the systems of record, so there is no backup requirement: recovery is to destroy
the volume and re-seed.

**The instance is read-only** (`sdlc.read-only`, part of [#48](../../../issues/48)) until
authentication exists. The deploy verifies that from the outside by sending a write and expecting
403.

**It deploys only what a green CI run on `main` tested.** `.github/workflows/deploy-dogfood.yml`
runs on the completion of CI, deploys only a successful `push` run on `main`, checks out that run's
own `head_sha`, builds the images from it, and deploys them by digest. It finishes by checking the
live instance (`fly/verify.sh`) and fails the deploy if the checks fail.

fly.io is one implementation of the deployment contract #48 describes, not a constraint on adopters.
Nothing outside `fly/` and the one workflow depends on it. The frontend image reads its backend
address and DNS resolver from the environment, and the compose defaults are unchanged.

## Consequences

- The first request after an idle period starts the web and API machines, and a cold JVM takes tens
  of seconds. That is the price of an instance that costs little.
- fly.io stopped offering its free allowance to new organisations in 2024. On a legacy allowance
  the three machines fit; otherwise, expect the always-on 512 MB database machine plus a small
  volume to cost a few dollars a month (`fly/README.md`).
- Deploys need two secrets on the `dogfood` GitHub environment, `FLY_API_TOKEN` and
  `NEO4J_PASSWORD`. Everything else on fly.io is created by `fly/bootstrap.sh` on the first deploy.
- Not yet done from #47: Prometheus and Alertmanager, the seed script and its schedule, deploying the
  digests CI builds rather than rebuilding them (#7), and running #48's conformance suite.
