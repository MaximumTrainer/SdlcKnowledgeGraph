# SDLC Knowledge Graph

A knowledge graph over code repositories. It models how a commit becomes a running service and what
that service depends on, so questions like these have an answer that can be queried rather than
reconstructed by hand:

- What depends on this change, and which infrastructure would it touch?
- Why did this deployment fail, and who owns the thing that broke?

Each repository is a node. Around it sit the teams that own it, the pipelines that build it, the
artifacts it produces, the environments those are deployed to, the cloud resources they run on, and
the configuration items that service management already tracks. Every fact carries its provenance.
Connectors that keep all of that in step with the systems of record are the next milestone; today
the graph is populated by hand, through the web interface or the API.

The graph is read by people through the web interface and by programs through a REST and GraphQL
API. Both sit behind an OIDC login when the deployment has an identity provider, and every write
records who made it ([ADR-0005](docs/adr/0005-auth-oidc-github-first.md)). Read and write scopes
apply to AI agents exactly as they apply to people; finer-grained access control is still to come.

See the [roadmap](docs/ROADMAP.md) for what is built and what is planned, issue by issue.

## Stack

| Part | Technology |
| --- | --- |
| Backend | Kotlin 2.0, Spring Boot 3.4, Java 17, hexagonal architecture |
| Graph store | Neo4j 5 |
| API | REST documented with OpenAPI, and GraphQL |
| Frontend | Vue 3, TypeScript, Vite |
| Tests | JUnit 5, Cucumber, Testcontainers, Pact, Vitest, Playwright |
| Gates | lefthook, commitlint, ktlint, detekt, ESLint, Prettier, secretlint, GitHub Actions |

## Prerequisites

- JDK 17 or later. The Gradle build provisions a 17 toolchain if one is missing.
- Node.js 22 or later. The frontend toolchain (Vite 7) and the Pact consumer library require it.
- Docker, for the local database and for the integration, acceptance and contract tests.
| Website | VitePress, generated from the files in this repository |

## Getting started

The [getting started guide](docs/GETTING-STARTED.md) covers both ways of running the application
and walks through creating the first nodes. The short version:

```bash
docker compose up -d --build --wait   # everything in containers: UI on http://localhost:5173, API on 8080,
                                      # Keycloak on 8081; sign in as dan / dan
docker compose down -v                # stop, and wipe the graph
```

Or, for development, the database in a container and the application from source:

```bash
npm install                      # once, at the repository root: installs the git hooks

docker compose up -d neo4j keycloak   # Neo4j on 7687 (browser on :7474), Keycloak on 8081
cd backend && AUTH_ISSUER_URI=http://localhost:8081/realms/sdlc ./gradlew bootRun   # API on 8080
cd frontend && npm install && OIDC_AUTHORITY=http://localhost:8081/realms/sdlc npm run dev
                                      # UI on http://localhost:3000; sign in as dan / dan
```

Prerequisites: Docker, Node.js 20 or later, and a JDK 17 or later for running the backend from
source.

Useful endpoints once the backend is up:

| URL | What |
| --- | --- |
| `http://localhost:8080/swagger-ui.html` | Interactive REST API documentation |
| `http://localhost:8080/graphiql` | GraphiQL, for the GraphQL endpoint at `/graphql` |
| `http://localhost:8080/api/v1/ontology` | The ontology the running instance was built with |
| `http://localhost:8080/actuator/health` | Health, including Neo4j status |

The stack is behind a login: `http://localhost:5173` sends you to Keycloak, where the seeded
development user is `dan`, password `dan` ([Getting started](docs/GETTING-STARTED.md)). Running from
source, start `docker compose up -d neo4j keycloak` and the API with
`AUTH_ISSUER_URI=http://localhost:8081/realms/sdlc`, or read-only with `SDLC_READ_ONLY=true` and no
identity provider. Connectors and agents sign in as registered service principals
([Authentication](docs/AUTH.md)).
Every request is decided by one authorisation policy in Rego, which the API evaluates itself
([Governance](docs/GOVERNANCE.md)). `--profile governance` adds an Open Policy Agent server that
serves the same policy, to explore it; the API does not need it.

## Tests

```bash
cd backend
./gradlew test              # unit tests, fast, no Docker
./gradlew check             # every suite, plus ktlint, detekt and the ontology drift check

cd frontend
npm run test:unit -- --run  # Vitest (without --run it stays in watch mode)
npm run test:contract       # Pact consumer tests, regenerating contracts/pacts/
npm run verify              # lint, typecheck, unit tests, contract tests, build

cd e2e
npm ci && npx playwright install --with-deps chromium   # once
npx playwright test         # starts the compose stack, then runs the browser tests against it
```

The backend has four suites: `test` for unit tests, `integrationTest` for adapters against a real
Neo4j, `acceptanceTest` for Gherkin features driving the running application, and `contractTest` for
Pact verification. The last three need Docker.

## How work is done here

Every change is developed outside-in, and the commit history is expected to show it: the acceptance
test is committed while it still fails, then the contract test, then unit tests, then the code that
makes them pass. Pull requests are asked for the commit SHA of each step.

Gates enforce the parts a machine can check:

| When | What runs |
| --- | --- |
| On commit message | Conventional format, known scope, and an issue reference |
| On commit | Format and lint staged files; check generated files against the ontology and the docs; refuse secrets, oversized files, conflict markers, and commits on `main` |
| On push | Full backend `check`, the frontend verify chain, and a check that the committed pacts are current |
| On pull request and on `main` | All of the above (except the branch guard), plus Prettier, the website build and its tests, and browser end-to-end tests |

Commits deliberately run no tests: this workflow commits a failing test before the code that passes
it. The refusals are separate from the linting, because a leaked credential or a large binary cannot
be undone by a later commit ([ADR-0007](docs/adr/0007-commit-guards.md)).

[docs/TESTING.md](docs/TESTING.md) explains the loop, the suites and the gates in more detail.

## Documentation

| Document | Contents |
| --- | --- |
| [Getting started](docs/GETTING-STARTED.md) | Running the application and creating the first nodes |
| [User guide](docs/USER-GUIDE.md) | The web interface, the REST and GraphQL APIs, and what each refuses |
| [Roadmap](docs/ROADMAP.md) | Milestones, the issues in each, and their status |
| [Ontology](docs/ONTOLOGY.md) | Entity types, relationships, provenance, identity |
| [Adapters](docs/ADAPTERS.md) | The connector contract, as designed for the next milestone |
| [Testing](docs/TESTING.md) | The TDD loop, the test suites, and the gates |
| [Observability](docs/OBSERVABILITY.md) | Following a request through the logs, and the log format |
| [Deployment contract](docs/DEPLOYMENT.md) | What every deployment must provide, and the suite that checks it |
| [Dogfood instance](docs/DOGFOOD.md) | The project's own public instance: what it holds, and why it is read-only |
| [White paper](docs/AGENT-SYSTEMS-WHITE-PAPER.md) | How the graph gives software agents a world model they can query |
| [Decisions](docs/adr/) | Architecture decision records, and why each was made |

All of it is published at
[maximumtrainer.github.io/SdlcKnowledgeGraph](https://maximumtrainer.github.io/SdlcKnowledgeGraph/),
generated from these same files plus the ontology registry and the OpenAPI document, so the
published model is the real one. Nothing under `website/src/` is written by hand; see
[ADR-0006](docs/adr/0006-website-generated-from-sources.md).

```bash
cd website
npm install
npm run generate      # rewrite the pages from their sources
npm run dev           # preview; stop the compose frontend first, or pass --port, as both default to 5173
npm run verify        # drift, unit tests, build, browser tests
```
