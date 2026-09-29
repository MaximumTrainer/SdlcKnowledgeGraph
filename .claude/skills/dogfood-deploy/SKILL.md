---
name: dogfood-deploy
description: Deploy, inspect, re-seed and recover the dogfood instance on fly.io (sdlc-graph.fly.dev). Use when a Deploy dogfood or Dogfood seed run fails, when asked what the live instance is running or holds, or when its database needs rebuilding.
---

# The dogfood instance

`docs/DOGFOOD.md` says what it is and holds; `fly/README.md` is the setup and the operating manual.
The rule that shapes everything below: **the graph is disposable**. Every fact in it is re-derivable,
so a broken database is destroyed and re-seeded, never restored.

## Deploying

Never run `flyctl deploy` by hand. `.github/workflows/deploy-dogfood.yml` deploys every green `push`
run of CI on `main`, from that run's commit, building the images and deploying them **by digest**
(`--image registry.fly.io/<app>@sha256:...`), so what runs is exactly what CI tested. To redeploy,
re-run the latest Deploy dogfood run from the Actions tab. A red run on `main` deploys nothing.

A deploy is only done when `fly/verify.sh` and the conformance suite pass against the public address,
and, when `ALERTMANAGER_WEBHOOK_URL` is set, `deploy-check` passes inside the monitoring machine.

## Reading what it runs and logs

```bash
curl -s https://sdlc-graph.fly.dev/actuator/info | jq .deployment   # commit, read-only, ontology
flyctl logs --app sdlc-graph-backend                  # or sdlc-graph, sdlc-graph-neo4j, sdlc-graph-monitoring
flyctl status --app sdlc-graph-backend
```

The container cannot reach fly.io or `*.fly.dev`; from a cloud session, read the workflow runs
instead (`actions/runs?branch=main` on the GitHub API) and their logs.

## When an alert fires

The alert names its runbook (`docs/runbooks/`). Prometheus and Alertmanager are private; reach them
with `flyctl proxy 9090 --app sdlc-graph-monitoring` (or 9093). If `deploy-check` fails, read which
line failed: not scraping means the API or its flycast address; no rules means the image; no
delivery means the webhook URL or the receiver.

## Checking it against the contract

```bash
cd e2e && npm ci
CONFORMANCE_BASE_URL=https://sdlc-graph.fly.dev EXPECTED_COMMIT=<sha> \
  npx playwright test --config=conformance.config.ts
```

See the deploy-conformance skill for reading a failure back to its requirement.

## Re-seeding

Run the Dogfood seed workflow (`.github/workflows/dogfood-seed.yml`) from the Actions tab; it also
runs daily. It is safe to run any number of times: identity is derived, so a rerun updates properties
and never adds nodes. By hand:

```bash
GITHUB_REPOSITORY=MaximumTrainer/SdlcKnowledgeGraph SEED_BASE_URL=https://sdlc-graph.fly.dev \
  INGEST_TOKEN=... node scripts/dogfood-seed.mjs
```

If it fails with "more than the ceiling", something other than the seed and the deploy is writing to
the instance: find out what before raising `SEED_NODE_CEILING`.

## Recovering a corrupt database

Destroy the Neo4j machine and volume (`fly/README.md`, "Recovery"), re-run the latest Deploy dogfood
run so it creates a fresh volume, then run the Dogfood seed. Do not look for a backup; there is none
by design (ADR-0010).
