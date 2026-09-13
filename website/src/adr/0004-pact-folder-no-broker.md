<!-- GENERATED FROM docs/adr/0004-pact-folder-no-broker.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0004: Verify Pact contracts from a folder, without a broker

## Status

Accepted. Implemented by [#17](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/17).

## Context

The frontend and backend are separate deployables with a shared HTTP contract. Outside-in TDD means
the frontend's expectation of an endpoint should exist as a test before the endpoint does.

Pact is already a declared dependency, and a `contractTest` Gradle task already exists. Neither does
anything useful: the only test in the suite prints a line and asserts nothing, and the task is
configured with `isFailOnNoMatchingTests = false`, so it reports success while verifying nothing.
That is worse than having no contract tests, because the green check implies coverage that does not
exist.

The standard Pact deployment uses a broker: consumers publish pacts, providers fetch and verify
them, and results are recorded centrally with versioning and can-i-deploy checks. That is the right
answer for many teams and independently deployed services.

It is not the right answer here yet. A broker is another service to run, secure and keep available,
and CI would depend on it. Both sides of this contract live in one repository and are released
together, so the coordination problem a broker solves does not currently exist.

## Decision

Consumer tests in the frontend, under `frontend/src/services/__pact__/`, generate pact files into
`contracts/pacts/` at the repository root. Those files are committed.

The backend `contractTest` suite verifies against those files with `@PactFolder`, needing no network
and no broker.

Remove the tolerance for an empty suite once real verification exists, so that deleting the tests
turns the build red instead of green.

Consumer and provider names are `sdlc-graph-frontend` and `sdlc-graph-backend`.

Each interaction names a provider state, and the provider seeds it from an empty graph through
`GraphStore`, so verification never passes on data an earlier interaction left behind. A unit test
holds the handler set and the states named by the pacts to the same shape.

CI checks that the committed pacts match what the consumer tests generate, so a change to frontend
expectations cannot be committed without the corresponding pact file.

## Consequences

A change to what the frontend expects produces a changed pact file in the diff, which is reviewable,
and it fails the provider suite until the backend complies. The contract becomes visible in code
review rather than living in a separate system.

CI has no external dependency for contract verification, and the tests run offline. The provider
side does need Docker, because it verifies the real application against a real Neo4j rather than a
mocked store; that is the same dependency the integration and acceptance suites already have.

See [Testing](/guide/testing#contract-tests) for how to run both sides.

Committed generated files can drift if someone edits them by hand or forgets to regenerate. The CI
drift check exists for that.

This does not scale to consumers outside this repository. If a second consumer appears, or the two
sides start releasing independently, a broker becomes worth its cost and this decision should be
revisited. The consumer and provider tests themselves would carry over; only the resolution
mechanism changes.
