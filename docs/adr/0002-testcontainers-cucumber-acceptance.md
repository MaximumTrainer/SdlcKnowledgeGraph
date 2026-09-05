# ADR-0002: Acceptance tests with Cucumber against a Testcontainers Neo4j

## Status

Accepted.

## Context

Each issue in this project carries acceptance criteria written as Given/When/Then. For those
criteria to be more than prose, the same text has to be executable, and it has to be written before
the implementation.

The application is a graph over Neo4j. Most of its interesting logic is Cypher: traversals,
uniqueness constraints, and merge semantics. Before this change, Neo4j was a hard dependency with no
test double, no compose file and no integration test, so nothing below the service layer could be
tested at all and the most logic-dense file in the codebase had no coverage.

Mocking the database was rejected. A mock of a graph store asserts that we called the methods we
think we call, which is exactly the assumption that is wrong when a traversal returns nothing. The
existing `BUILT_FROM` bug is an example: a query matches a relationship that no code ever writes, and
no amount of mocking would reveal it.

## Decision

Use Cucumber-JVM on the JUnit Platform for acceptance tests. Feature files live in
`backend/src/acceptanceTest/resources/features` and are copied from the issue that requested them.
Step definitions are Kotlin and drive the application from outside over HTTP.

Use Testcontainers to run a real Neo4j for the `integrationTest` and `acceptanceTest` suites, wired
into Spring with `@ServiceConnection` so no connection properties are needed in test configuration.

Separate the work into four Gradle test suites, so that cost matches the gate:

| Suite | Needs Docker | Purpose |
| --- | --- | --- |
| `test` | no | Unit tests, no Spring context |
| `integrationTest` | yes | Spring slices and adapters against real Neo4j |
| `acceptanceTest` | yes | Gherkin features against the running application |
| `contractTest` | no | Pact provider verification |

Share helpers between suites through `backend/src/testSupport/kotlin`, compiled into both suites that
need them.

Reset state between scenarios with a Cucumber `@Before` hook that deletes all nodes, so scenarios
cannot depend on each other.

## Consequences

The acceptance criteria in an issue are executable, which means they can be committed red before the
implementation exists. This is what makes the outside-in loop checkable by a reviewer.

Cypher is tested against Neo4j rather than against an assumption, so a query that matches nothing
fails a test rather than silently returning an empty list.

Docker becomes a requirement for running the full check locally and in CI. The `test` suite stays
free of that requirement, which is why the pre-commit hook runs only that suite.

Container startup costs time on every run. Testcontainers reuse mitigates this locally, enabled with
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`. CI creates a fresh container
each run.

Kotlin is used for step definitions rather than a separate language, so refactoring tools apply to
them.
