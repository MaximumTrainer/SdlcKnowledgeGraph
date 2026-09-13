---
layout: home
hero:
  name: SDLC Knowledge Graph
  text: How a commit becomes a running service
  tagline: A queryable graph of repositories, teams, pipelines, artifacts, deployments, environments and cloud resources, with the provenance of every fact.
  image:
    src: /logo.svg
    alt: SDLC Knowledge Graph
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: User guide
      link: /guide/user-guide
    - theme: alt
      text: Ontology reference
      link: /reference/ontology
features:
  - title: One model, declared once
    details: Node and relationship types live in a YAML registry. The API, the GraphQL types, the frontend types and this site are generated from it.
    link: /guide/ontology
  - title: Every fact has a source
    details: Each node and edge carries provenance - which system reported it, when, and how confident it was - so a wrong answer can be traced and corrected.
    link: /guide/ontology#provenance
  - title: Built outside-in
    details: Acceptance tests are committed red before the code that makes them pass, and git hooks refuse what cannot be undone.
    link: /guide/testing
---
<!-- GENERATED FROM README.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

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
API. Access control that applies to AI agents exactly as it applies to people is designed
([ADR-0005](/adr/0005-auth-oidc-github-first)) but not yet built: there is currently no
authentication.

See the [roadmap](/reference/roadmap) for what is built and what is planned, issue by issue.

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

The [getting started guide](/guide/getting-started) covers both ways of running the application
and walks through creating the first nodes. The short version:

```bash
docker compose up -d --build --wait   # everything in containers: UI on http://localhost:5173, API on 8080
docker compose down -v                # stop, and wipe the graph
```

Or, for development, the database in a container and the application from source:

```bash
npm install                      # once, at the repository root: installs the git hooks

docker compose up -d neo4j       # Neo4j on 7687, browser on http://localhost:7474
cd backend && ./gradlew bootRun  # API on http://localhost:8080
cd frontend && npm install && npm run dev   # UI on http://localhost:3000
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

Two optional compose profiles exist for work that has not started: `--profile auth` adds Keycloak
and `--profile governance` adds Open Policy Agent. Nothing uses either yet.

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
be undone by a later commit ([ADR-0007](/adr/0007-commit-guards)).

[docs/TESTING.md](/guide/testing) explains the loop, the suites and the gates in more detail.

## Documentation

| Document | Contents |
| --- | --- |
| [Getting started](/guide/getting-started) | Running the application and creating the first nodes |
| [User guide](/guide/user-guide) | The web interface, the REST and GraphQL APIs, and what each refuses |
| [Roadmap](/reference/roadmap) | Milestones, the issues in each, and their status |
| [Ontology](/guide/ontology) | Entity types, relationships, provenance, identity |
| [Adapters](/guide/adapters) | The connector contract, as designed for the next milestone |
| [Testing](/guide/testing) | The TDD loop, the test suites, and the gates |
| [Decisions](/adr/) | Architecture decision records, and why each was made |

All of it is published at
[maximumtrainer.github.io/SdlcKnowledgeGraph](https://maximumtrainer.github.io/SdlcKnowledgeGraph/),
generated from these same files plus the ontology registry and the OpenAPI document, so the
published model is the real one. Nothing under `website/src/` is written by hand; see
[ADR-0006](/adr/0006-website-generated-from-sources).

```bash
cd website
npm install
npm run generate      # rewrite the pages from their sources
npm run dev           # preview; stop the compose frontend first, or pass --port, as both default to 5173
npm run verify        # drift, unit tests, build, browser tests
```
