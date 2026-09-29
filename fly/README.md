# fly.io: the dogfood instance

The configuration for the project's own public instance, `https://sdlc-graph.fly.dev` by default.
[ADR-0010](../docs/adr/0010-dogfood-on-fly-io.md) explains why fly.io, and why the graph it holds
is disposable.

| File | What |
| --- | --- |
| `fly.frontend.toml` | The web interface: the only public app |
| `fly.backend.toml` | The API, private (`.flycast`), read-only |
| `fly.neo4j.toml` | Neo4j on a volume, private (`.internal`), no services |
| `bootstrap.sh` | Creates whatever is missing: apps, volume, the backend's private address, secrets |
| `verify.sh` | Checks the live instance from outside, and fails the deploy if it is wrong |

## How a deploy happens

Nobody runs `flyctl deploy` by hand. `.github/workflows/deploy-dogfood.yml` runs when CI completes,
and deploys only a green `push` run on `main`, from that run's own commit. In order, it runs
`bootstrap.sh`, deploys Neo4j, builds and deploys the API and then the web interface by image
digest, and runs `verify.sh`. Besides reads, the refused write and the private addresses, that
checks that the API reports the commit the run built on `/actuator/info` (read over `flyctl ssh`,
since `/actuator` is not public) and that `/actuator` cannot be reached from the internet.

## One-time setup

1. Create a deploy token for the fly.io organisation:
   `fly tokens create org --org personal --expiry 8760h`
2. In the repository's settings, create the `dogfood` environment, and add two secrets to it:
   - `FLY_API_TOKEN`: the token from step 1
   - `NEO4J_PASSWORD`: any random string of 8 characters or more, for example the output of
     `openssl rand -hex 24`
3. If the app names are taken on fly.io, or the organisation or region should differ, change
   `FLY_APP_PREFIX` (default `sdlc-graph`), `FLY_ORG` (`personal`) and `FLY_REGION` (`lhr`) at the
   top of the deploy job in `.github/workflows/deploy-dogfood.yml`.

The next green run on `main` creates everything else.

## Machines and cost

| App | Machine | Running |
| --- | --- | --- |
| `sdlc-graph` | shared-cpu-1x, 256 MB | When visited; stops when idle |
| `sdlc-graph-backend` | shared-cpu-1x, 512 MB | When visited; stops when idle |
| `sdlc-graph-neo4j` | shared-cpu-1x, 512 MB, 1 GB volume | Always |

fly.io no longer gives new organisations a free allowance. On a legacy allowance, this fits. Otherwise
the cost is mostly the always-on database machine and its volume, a few dollars a month. The first
visit after an idle period waits for the web and API machines to start.

## Operating it

```bash
flyctl logs --app sdlc-graph-backend       # or sdlc-graph, sdlc-graph-neo4j
flyctl status --app sdlc-graph-backend
FLY_APP_PREFIX=sdlc-graph fly/verify.sh    # the post-deploy checks, run by hand
```

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

Then re-run the latest Deploy dogfood run from the Actions tab. There is nothing to re-seed yet:
the seed script is still to come (#47).
