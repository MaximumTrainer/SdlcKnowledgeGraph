<!-- GENERATED FROM docs/TESTING.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Testing and the TDD workflow

Work on this project is done outside-in: the test that describes the behaviour a user asked for is
written before the code that provides it, and it is committed while it still fails.

## The loop

1. **Acceptance test, red.** Copy the Gherkin from the issue into
   `backend/src/acceptanceTest/resources/features/<slug>.feature` and write the missing step
   definitions. If the change is visible in the browser, add `e2e/tests/<slug>.spec.ts` as well.
   Run it, watch it fail for the right reason, and commit it.
2. **Contract or API test, red.** A Pact consumer test in
   `frontend/src/services/__pact__/` if the frontend calls a new endpoint, or a `@WebMvcTest` slice
   if only status codes and shapes are at stake. Commit it.
3. **Unit tests.** Now that the shape of the API is fixed, drive the internals with fast tests that
   need neither Spring nor Docker.
4. **Green.** Write the implementation. Commit when the acceptance test passes.

The pull request template asks for the SHA of each step. That is the evidence the loop was followed,
and it is the thing a reviewer checks first.

Writing the acceptance test after the code is not the same activity. It tends to describe what the
code happens to do rather than what was asked for, and it cannot fail in the way that proves it is
testing anything.

## Suites

Backend suites are separate Gradle test suites, so the fast ones can run on every commit and the
slow ones only on push.

| Suite | Location | Needs Docker | Runs at |
| --- | --- | --- | --- |
| `test` | `backend/src/test` | no | pre-commit, pre-push, CI |
| `integrationTest` | `backend/src/integrationTest` | yes | pre-push, CI |
| `acceptanceTest` | `backend/src/acceptanceTest` | yes | pre-push, CI |
| `contractTest` | `backend/src/contractTest` | yes | pre-push, CI |

`backend/src/testSupport/kotlin` holds helpers shared by more than one suite, currently the
Testcontainers Neo4j configuration. It is compiled into `integrationTest`, `acceptanceTest` and
`contractTest`.

Unit tests must not start a Spring context. If a test needs one, it belongs in `integrationTest`.

Commands:

```bash
cd backend
./gradlew test              # unit only, fast
./gradlew integrationTest   # Spring plus real Neo4j
./gradlew acceptanceTest    # Gherkin features
./gradlew contractTest      # Pact provider verification
./gradlew check             # everything above, plus ktlint and detekt
```

Frontend:

```bash
cd frontend
npm run test:unit           # Vitest, with MSW for HTTP
npm run test:contract       # Pact consumer tests, regenerating contracts/pacts/
npm run verify              # lint, typecheck, unit tests, contract tests, build

cd e2e
npx playwright test         # browser tests against the compose stack
```

## Lint

```bash
cd backend
./gradlew ktlintCheck     # reports in build/reports/ktlint/, plain and checkstyle
./gradlew ktlintFormat    # fixes what it can
./gradlew detekt          # reports in build/reports/detekt/, html and sarif

cd frontend
npm run lint              # eslint .
npm run lint:fix          # eslint . --fix
npm run format:check      # prettier --check .

cd e2e
npm run lint              # reuses the frontend flat config

npx --yes actionlint@latest .github/workflows/*.yml   # GitHub workflow files
```

## Gates

| Hook | What runs |
| --- | --- |
| `commit-msg` | commitlint: conventional format, known scope, issue reference required |
| `pre-commit` | ktlint format and restage, detekt, ESLint and Prettier on staged files, actionlint on workflows |
| `pre-push` | `./gradlew check` and the frontend verify chain |

Note what `pre-commit` does not do: run tests. That is deliberate. This workflow requires committing
a failing acceptance test before the code that satisfies it, so a hook that ran tests on commit
would make the required process impossible without bypassing it, and a bypass that becomes routine
is not a gate.

Tests run on push instead, against the state actually being shared. Intermediate red commits inside
a branch are expected and fine, as long as the tip of the branch is green.

CI re-runs all of it and adds the browser end-to-end job, because a hook can be skipped and a CI
check cannot.

Hooks are installed by `npm install` at the repository root, which runs `lefthook install`. If hooks
are not firing, run it again and confirm `.git/hooks/pre-commit` exists.

`LEFTHOOK=0 git commit` bypasses the hooks. It exists for emergencies such as committing a fix while
the toolchain itself is broken. Using it to avoid a failing test defeats the point of having gates,
and CI will fail anyway.

## Testcontainers

`integrationTest` and `acceptanceTest` start a real Neo4j in Docker rather than mocking the database,
because most of the interesting bugs in a graph application live in the Cypher.

Docker must be running. To keep the container alive between runs instead of paying startup on every
invocation, add this to `~/.testcontainers.properties`:

```properties
testcontainers.reuse.enable=true
```

Reuse is a local convenience. CI creates a fresh container each run.

Every acceptance scenario starts from an empty graph: a Cucumber `@Before` hook runs
`MATCH (n) DETACH DELETE n`. Scenarios must not depend on each other's data.

## Contract tests

The frontend and backend are tested against each other without running both at once. Consumer tests
in `frontend/src/services/__pact__/` produce pact files into `contracts/pacts/`, which are committed.
The backend's `contractTest` suite verifies itself against those files using `@PactFolder`, so no
Pact broker is needed (see [ADR-0004](/adr/0004-pact-folder-no-broker)).

If the frontend changes what it expects, the pact file changes, and the backend suite fails until it
complies. That is the point.

### The consumer side

`npm run test:contract` runs only `src/**/*.pact.spec.ts`, in the node environment and one file at a
time, because Pact starts an HTTP mock server per file. It deletes `contracts/pacts/` first: Pact
merges into an existing document, so without that an interaction you renamed would linger and the
committed contract would stop being a faithful render of the specs.

A consumer test drives the real functions in `src/services/api.ts` — pointing `apiClient` at the mock
server — rather than a copy of the request they make. A pact written against a hand-rolled `fetch`
documents the test, not the application.

### The provider side

`./gradlew contractTest` boots the application on a random port against a Testcontainers Neo4j and
replays every interaction. Each one names a **provider state**: the graph the backend must be in for
the interaction to make sense. The handlers live in
`backend/src/contractTest/.../ProviderStates.kt`, every one of them starting from an empty graph, and
`ProviderStatesTest` asserts that the set of handlers and the set of states named by the committed
pacts are the same. Adding an interaction with a new state therefore fails fast and locally, rather
than as a verification error later.

An empty `contracts/pacts/` fails the suite with `NoPactsFoundException`. Deleting the contract tests
turns the build red, not green.

### Keeping the two in step

`pre-push` and CI run `git diff --exit-code -- contracts/pacts` after the consumer tests, so a change
to what the frontend expects cannot be pushed without the regenerated pact that the provider will be
verified against.

## Website tests

The website is generated from the sources in this repository, so its tests assert the built site
against those sources rather than against fixed text: the ontology reference is checked against every
node type in `ontology.json`, and the ADR index against every file in `docs/adr`. A node type or a
record added later is therefore covered on the day it is added.

```bash
cd website
npm run test:unit     # the generators, including link rewriting
npm run drift         # fails if a page is stale, or if a page nothing generates exists
npm run test:site     # Playwright against a real build served by vitepress preview
npm run verify        # all of the above, plus the build
```

`npm run drift` is what `pre-commit` runs when a source changes. The build itself is a test too:
`ignoreDeadLinks` is off, so a link to a page that does not exist fails it.

## Writing a good acceptance test

Drive the application from outside, through HTTP or the browser. Do not reach into Spring beans to
set up state that a user would have to create through the API.

Assert on what the user asked for. `Then the response status is 404 and the body names the missing
node` is a requirement. `Then upsertEdge throws NodeNotFoundException` is an implementation detail
that will make the test fail the next time someone refactors correctly.

Keep step definitions thin. They translate Gherkin into HTTP calls and assertions, nothing more.
