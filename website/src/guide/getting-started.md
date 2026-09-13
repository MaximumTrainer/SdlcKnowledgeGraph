<!-- GENERATED FROM docs/GETTING-STARTED.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Getting started

This page gets a graph running on your machine and puts the first few nodes in it. It covers two
ways of running the application: everything in containers, which is the quickest way to try it, and
the database in a container with the backend and frontend run from source, which is how development
is done.

What you will have at the end:

| Where | What |
| --- | --- |
| `http://localhost:5173` (containers) or `http://localhost:3000` (from source) | The web interface |
| `http://localhost:8080/api/v1/…` | The REST API |
| `http://localhost:8080/swagger-ui.html` | Interactive REST documentation |
| `http://localhost:8080/graphiql` | GraphiQL, for the GraphQL endpoint at `/graphql` |
| `http://localhost:8080/actuator/health` | Health, including the Neo4j connection |
| `http://localhost:7474` | The Neo4j browser (user `neo4j`, password `password`) |

## Prerequisites

- Docker, with Docker Compose v2. The database always runs in a container, and so do the
  integration, acceptance and contract tests.
- Node.js 20 or later, for the frontend, the website and the git hooks.
- JDK 17 or later, only if you run the backend from source. The Gradle wrapper provisions a 17
  toolchain when none is installed, but needs some JDK to start from.

## Option A: everything in containers

From the repository root:

```bash
docker compose up -d --build --wait
```

That builds the backend and frontend images, starts Neo4j, and waits until every container reports
healthy. The first build downloads Gradle and npm dependencies and takes a few minutes; later builds
are cached.

Open `http://localhost:5173`. You should see the web interface with a row of tabs, one per node
type, and an empty **Repository** list.

To stop:

```bash
docker compose down        # stop, keep the data
docker compose down -v     # stop, and wipe the graph
```

Two optional profiles exist for work that has not started yet: `--profile auth` adds Keycloak on
port 8081 and `--profile governance` adds Open Policy Agent on port 8181. Nothing in the
application talks to either of them today, so leave them off.

## Option B: database in a container, application from source

This is the development loop. Run the three parts in separate terminals.

```bash
npm install                      # once, at the repository root: installs the git hooks

docker compose up -d neo4j       # Neo4j on bolt://localhost:7687, browser on http://localhost:7474

cd backend && ./gradlew bootRun  # API on http://localhost:8080

cd frontend && npm install && npm run dev   # UI on http://localhost:3000, proxying /api to 8080
```

The backend reads its Neo4j connection from `NEO4J_URI`, `NEO4J_USERNAME` and `NEO4J_PASSWORD`,
defaulting to the values `compose.yaml` uses, so nothing needs configuring for the local database.

Run `npm install` at the root even if you never touch the backend: it installs lefthook, which is
what enforces the commit and push gates described in [the testing guide](/guide/testing). Without it a commit
that fails those gates is only caught by CI.

## The first few nodes

The quickest way to see the graph working is to create a team, a repository, and the relationship
between them. The web interface does all of this; the same three steps through the API follow.

1. Open the **Team** tab, press **New**, enter a `name` such as `platform`, and **Save**. The
   node's page opens. Its key is the lowercased name.
2. Open the **Repository** tab and press **New**. Fill in `url` with any git remote, for example
   `https://github.com/acme/payments`, plus the properties marked `*`: `orgRepo` (`acme/payments`),
   `defaultBranch` (`main`), and at least an empty `topics` and `codeowners`. **Save**. The key is
   derived from the remote as `github.com/acme/payments`, so the same repository given as
   `git@github.com:acme/payments.git` would land on the same node.
3. On the repository's page, press **Add relationship**, choose **OWNED_BY**, type `plat` in the
   target box, pick `platform` from the suggestions, and press **Add**. The relationship appears
   under **OWNED_BY** here and under **OWNS** on the team's page: one edge, read from both ends.

The same thing with `curl`:

```bash
curl -s -X POST localhost:8080/api/v1/nodes/Team \
  -H 'content-type: application/json' \
  -d '{"props":{"name":"platform"}}'

curl -s -X POST localhost:8080/api/v1/nodes/Repository \
  -H 'content-type: application/json' \
  -d '{"props":{"url":"https://github.com/acme/payments","orgRepo":"acme/payments","defaultBranch":"main","topics":[],"codeowners":[]}}'

curl -s -X POST localhost:8080/api/v1/edges \
  -H 'content-type: application/json' \
  -d '{"type":"OWNED_BY","fromId":"Repository:github.com/acme/payments","toId":"Team:platform"}'

curl -s 'localhost:8080/api/v1/edges?nodeId=Team:platform'
```

Node ids are `Type:key`. The create responses return the id and key the server derived, and every
refusal says what was wrong and, where it can, what would have been accepted; the
[user guide](/guide/user-guide) lists them.

## Checking it is healthy

```bash
curl -s localhost:8080/actuator/health
```

`{"status":"UP"}` with a `neo4j` component means the backend can reach the database. If the status
is `DOWN`, the usual cause is Neo4j still starting: the container takes twenty seconds or so on
first run, and `docker compose up --wait` is what waits for it.

`GET /api/v1/ontology` returns the registry the running instance was built with, including its
version. The web interface reads this on every page, so if the interface shows no tabs, that
request is what to look at in the browser's network panel.

## Running the tests

The short version, for a contributor checking a change before pushing:

```bash
cd backend && ./gradlew check           # every backend suite plus ktlint and detekt; needs Docker
cd frontend && npm run verify           # lint, typecheck, unit tests, contract tests, build
cd e2e && npm ci && npx playwright install --with-deps chromium && npx playwright test
cd website && npm ci && npm run verify  # drift check, generator tests, build, browser tests
```

The end-to-end suite starts the compose stack itself and runs against it on port 5173.
[The testing guide](/guide/testing) describes each suite, what runs on commit and push, and why tests are
deliberately not run on commit.

## Where to go next

- The [user guide](/guide/user-guide) explains the web interface and the API in full.
- The [ontology guide](/guide/ontology) explains the model: the node and relationship types, how identity is
  derived, and what provenance is recorded.
- The [roadmap](/reference/roadmap) says what is built and what is planned, issue by issue.
