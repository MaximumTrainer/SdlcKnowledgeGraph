<!-- GENERATED FROM README.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

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

See [docs/ROADMAP.md](/reference/roadmap) for what is built and what is planned.

## Stack

| Part | Technology |
| --- | --- |
| Backend | Kotlin 2.0, Spring Boot 3.4, Java 17, hexagonal architecture |
| Graph store | Neo4j 5 |
| API | REST and GraphQL, documented with OpenAPI |
| Frontend | Vue 3, TypeScript, Vite |
| Tests | JUnit 5, Cucumber, Testcontainers, Pact, Vitest, Playwright |
| Gates | lefthook, commitlint, ktlint, detekt, ESLint, Prettier, secretlint, GitHub Actions |

## Prerequisites

- JDK 17 or later. The Gradle build provisions a 17 toolchain if one is missing.
- Node.js 22 or later. The frontend toolchain (Vite 7) and the Pact consumer library require it.
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
| On commit | Format and lint staged files; refuse secrets, oversized files, conflict markers, and commits on `main` |
| On push | Full backend `check`, and the frontend verify chain |
| On pull request | All of the above, plus browser end-to-end tests |

Commits deliberately run no tests: this workflow commits a failing test before the code that passes
it. The refusals are separate from the linting, because a leaked credential or a large binary cannot
be undone by a later commit ([ADR-0007](/adr/0007-commit-guards)).

[docs/TESTING.md](/guide/testing) explains the loop and the suites in more detail.

## Documentation

| Document | Contents |
| --- | --- |
| [docs/ROADMAP.md](/reference/roadmap) | Milestones and the issues in each |
| [docs/ONTOLOGY.md](/guide/ontology) | Entity types, relationships, provenance, identity |
| [docs/ADAPTERS.md](/guide/adapters) | The connector contract and how code is linked to infrastructure |
| [docs/TESTING.md](/guide/testing) | The TDD loop, the test suites, and the gates |
| [docs/adr/](/adr/) | Architecture decisions and why they were made |

All of it is also published as a website, generated from these same files plus the ontology registry
and the OpenAPI document, so the published model is the real one. Nothing under `website/src/` is
written by hand; see [ADR-0006](/adr/0006-website-generated-from-sources).

```bash
cd website
npm install
npm run generate      # rewrite the pages from their sources
npm run dev           # preview on http://localhost:5173
npm run verify        # drift, unit tests, build, browser tests
```
