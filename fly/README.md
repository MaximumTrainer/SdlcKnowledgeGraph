# fly.io: the dogfood instance

The configuration for the project's own public instance, `https://sdlc-graph.fly.dev` by default.
[ADR-0010](../docs/adr/0010-dogfood-on-fly-io.md) explains why fly.io, and why the graph it holds
is disposable.

| File | What |
| --- | --- |
| `fly.frontend.toml` | The web interface: the only public app |
| `fly.backend.toml` | The API, private (`.flycast`), read-only |
| `fly.neo4j.toml` | Neo4j on a volume, private (`.internal`), no services |
| `fly.monitoring.toml` | Prometheus and Alertmanager (`ops/monitoring`), private, no services |
| `bootstrap.sh` | Creates whatever is missing: apps, volume, the backend's private address, secrets |
| `verify.sh` | Checks the live instance from outside, and fails the deploy if it is wrong |

## How a deploy happens

Nobody runs `flyctl deploy` by hand. `.github/workflows/deploy-dogfood.yml` runs when CI completes,
and deploys only a green `push` run on `main`, from that run's own commit: its first job asks the
deploy-on-green gate ([docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md#deploying-on-green)) whether to. In order, it runs
`bootstrap.sh`, deploys Neo4j, builds and deploys the API, the web interface and the monitoring by
image digest, runs `verify.sh`, and then runs the deployment conformance suite (`e2e/conformance`, the
contract in [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)) against the public address, with the commit
it built as `EXPECTED_COMMIT`. Last, when alerts have a receiver, it runs `deploy-check` inside
the monitoring machine, which proves an alert reaches it (D10). A deploy that fails any of these is
red.

## One-time setup

1. Create a deploy token for the fly.io organisation:
   `fly tokens create org --org personal --expiry 8760h`
2. In the repository's settings, create the `dogfood` environment, and add two secrets to it:
   - `FLY_API_TOKEN`: the token from step 1
   - `NEO4J_PASSWORD`: any random string of 8 characters or more, for example the output of
     `openssl rand -hex 24`

   A third secret is optional:
   - `INGEST_TOKEN`: another random string. With it, every deploy records itself in the graph it
     deployed, through `POST /api/v1/ingest/deployment` (docs/ADAPTERS.md, "Self-ingestion"): which
     images, from which commit, and whether the deploy worked. It also lets the daily Dogfood seed
     workflow write this repository's own SDLC (docs/DOGFOOD.md). Without it, each deploy and each
     seed warns that it wrote nothing.
   - `ALERTMANAGER_WEBHOOK_URL`: where alerts are posted, as Alertmanager's webhook JSON. Any
     endpoint that accepts a POST will do, such as an [ntfy](https://ntfy.sh) topic URL. Without it
     alerts are evaluated but go nowhere, and each deploy warns so. With it, every deploy sends one
     `DeployCheck` alert, severity ticket, to prove delivery works.
3. If the app names are taken on fly.io, or the organisation or region should differ, change
   `FLY_APP_PREFIX` (default `sdlc-graph`), `FLY_ORG` (`personal`) and `FLY_REGION` (`lhr`) at the
   top of the deploy job in `.github/workflows/deploy-dogfood.yml`.

The next green run on `main` creates everything else.

## Machines and cost

| App | Machine | Running |
| --- | --- | --- |
| `sdlc-graph` | shared-cpu-1x, 256 MB | When visited; stops when idle |
| `sdlc-graph-backend` | shared-cpu-1x, 512 MB | Always: Prometheus scrapes it |
| `sdlc-graph-neo4j` | shared-cpu-1x, 512 MB, 1 GB volume | Always |
| `sdlc-graph-monitoring` | shared-cpu-1x, 256 MB, no volume | Always |

fly.io no longer gives new organisations a free allowance. On a legacy allowance, the three always-on
machines are what it covers. Otherwise the cost is the always-on machines and the volume, several
dollars a month. The API stays up because an API that stopped when idle would page as InstanceDown
every time nobody was using it. The first
visit after an idle period waits for the web and API machines to start.

## Operating it

```bash
flyctl logs --app sdlc-graph-backend       # or sdlc-graph, sdlc-graph-neo4j, sdlc-graph-monitoring
flyctl status --app sdlc-graph-backend
FLY_APP_PREFIX=sdlc-graph fly/verify.sh    # the post-deploy checks, run by hand
```

### Alerts

Prometheus and Alertmanager are private. To look at them, forward their ports to your machine:

```bash
flyctl proxy 9090 --app sdlc-graph-monitoring    # Prometheus: http://localhost:9090/alerts
flyctl proxy 9093 --app sdlc-graph-monitoring    # Alertmanager: http://localhost:9093
flyctl ssh console --app sdlc-graph-monitoring --command deploy-check   # prove delivery again
```

There is no Grafana here: the sync dashboard runs only in the local `monitoring` compose profile, to
keep the instance to the machines above ([OBSERVABILITY.md](../docs/OBSERVABILITY.md#dashboards)).
Query the proxied Prometheus for the same series.

The rules, the routes and the runbooks are in the repository ([OBSERVABILITY.md](../docs/OBSERVABILITY.md#alerts)).
Metrics history is not kept across deploys: the machine has no volume, and the burn-rate windows
fill again within their own length.

### Recovery: destroy and re-derive

The graph is not backed up, deliberately. If the database is corrupt, or the password has to change
(`NEO4J_AUTH` only takes effect when the database is first created), destroy the volume and let the
next deploy create a fresh one:

```bash
flyctl machines list --app sdlc-graph-neo4j
flyctl machines destroy <id> --app sdlc-graph-neo4j --force
flyctl volumes list --app sdlc-graph-neo4j
flyctl volumes destroy <volume-id> --app sdlc-graph-neo4j
```

Then re-run the latest Deploy dogfood run from the Actions tab, and once it is green run the Dogfood
seed workflow, which writes this repository back (docs/DOGFOOD.md). The deployment history starts
again from that deploy.
