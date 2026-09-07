# SDLC Knowledge Graph

A knowledge graph over code repositories. It models how a commit becomes a running service and what
that service depends on, so questions like these have an answer that can be queried rather than
reconstructed by hand:

- What depends on this change, and which infrastructure would it touch?
- Why did this deployment fail, and who owns the thing that broke?

Each repository is a node. Around it sit the teams that own it, the pipelines that build it, the
artifacts it produces, the environments those are deployed to, the cloud resources they run on, and
the configuration items that service management already tracks. Connectors keep all of that in step
with the systems of record, and every fact carries its provenance.

The graph is designed to be read by people through the web interface and by AI agents through a
query API, under the same access rules.

See [docs/ROADMAP.md](docs/ROADMAP.md) for what is built and what is planned.

## Stack

| Part | Technology |
| --- | --- |
| Backend | Kotlin 2.0, Spring Boot 3.4, Java 17, hexagonal architecture |
| Graph store | Neo4j 5 |
| API | REST and GraphQL, documented with OpenAPI |
| Frontend | Vue 3, TypeScript, Vite |
| Tests | JUnit 5, Cucumber, Testcontainers, Pact, Vitest, Playwright |
| Gates | lefthook, commitlint, ktlint, detekt, ESLint, Prettier, GitHub Actions |

## Prerequisites

- JDK 17 or later. The Gradle build provisions a 17 toolchain if one is missing.
- Node.js 20 or later.
- Docker, for the local database and for the integration, acceptance and contract tests.

## Getting started

```bash
npm install           # installs the git hooks, at the repository root
```

Run that first. It installs lefthook, which is what enforces the commit and push gates described
below.

The usual development loop runs the database in Docker and the application from source:

```bash
docker compose up -d neo4j       # Neo4j on 7687, browser on http://localhost:7474

cd backend && ./gradlew bootRun  # API on http://localhost:8080
cd frontend && npm install && npm run dev   # UI on http://localhost:3000
```

Useful endpoints once the backend is up:

| URL | What |
| --- | --- |
| `http://localhost:8080/swagger-ui.html` | REST API documentation |
| `http://localhost:8080/graphql` | GraphQL endpoint, with GraphiQL |
| `http://localhost:8080/actuator/health` | Health, including Neo4j status |

To run everything in containers instead:

```bash
docker compose up -d --build --wait   # UI on http://localhost:5173, API on 8080
docker compose down -v                # stop, and wipe the graph
```

Two optional profiles exist: `--profile auth` adds Keycloak as a local identity provider, and
`--profile governance` adds Open Policy Agent. Neither is needed yet.

## Tests

```bash
cd backend
./gradlew test              # unit tests, fast, no Docker
./gradlew check             # every suite, plus ktlint and detekt

cd frontend
npm run test:unit           # Vitest
npm run test:contract       # Pact consumer tests, regenerating contracts/pacts/
npm run verify              # lint, typecheck, unit tests, contract tests, build

cd e2e
npx playwright test         # browser tests against the compose stack
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
| On commit | Format and lint staged files, run fast unit tests |
| On push | Full backend `check`, and the frontend verify chain |
| On pull request | All of the above, plus browser end-to-end tests |

[docs/TESTING.md](docs/TESTING.md) explains the loop and the suites in more detail.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Milestones and the issues in each |
| [docs/ONTOLOGY.md](docs/ONTOLOGY.md) | Entity types, relationships, provenance, identity |
| [docs/ADAPTERS.md](docs/ADAPTERS.md) | The connector contract and how code is linked to infrastructure |
| [docs/TESTING.md](docs/TESTING.md) | The TDD loop, the test suites, and the gates |
| [docs/adr/](docs/adr/) | Architecture decisions and why they were made |
