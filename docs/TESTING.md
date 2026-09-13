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

Backend suites are separate Gradle test suites, so the fast one can be run on its own in seconds
while the slow ones are left to push and CI.

| Suite | Location | Needs Docker | Runs at |
| --- | --- | --- | --- |
| `test` | `backend/src/test` | no | pre-push, CI |
| `integrationTest` | `backend/src/integrationTest` | yes | pre-push, CI |
| `acceptanceTest` | `backend/src/acceptanceTest` | yes | pre-push, CI |
| `contractTest` | `backend/src/contractTest` | yes | pre-push, CI |

`backend/src/testSupport/kotlin` holds helpers shared by more than one suite, currently the
Testcontainers Neo4j configuration. It is compiled into `integrationTest`, `acceptanceTest` and
`contractTest`.

Outside the backend there are three more suites, each on `node:test` with no extra framework:

| Suite | Location | Covers | Runs at |
| --- | --- | --- | --- |
| root | `scripts/*.test.mjs` | the commit guards themselves | CI, on Linux and Windows |
| website | `website/scripts/*.test.mjs` | the page generator | pre-commit drift check, CI |
| frontend | `frontend/src/**` | components and stores, with MSW | pre-push, CI |

The root suite exists because the guards are the one part of this repository whose failure is
silent: a guard that has stopped refusing looks exactly like a guard with nothing to refuse, since
commits keep succeeding either way. Each test drives the real script against a throwaway repository
under the OS temp directory - never the working repository, and never `origin`. It runs on both
platforms because path separators and line endings are where these scripts break.

```bash
npm run test:unit         # at the repository root
```

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
npm run test:unit -- --run  # Vitest, with MSW for HTTP; without --run it stays in watch mode
npm run test:contract       # Pact consumer tests, regenerating contracts/pacts/
npm run verify              # lint, typecheck, unit tests, contract tests, build

cd e2e
npm ci                                          # once
npx playwright install --with-deps chromium     # once
npx playwright test         # starts the compose stack itself, then runs the browser tests on :5173
```

`npm run verify` does not run `format:check`, but CI does, so run `npm run format:check` (or
`npm run format`) before pushing a frontend change; the pre-commit hook formats staged files, which
covers the usual case.

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

npm run actionlint -- .github/workflows/*.yml   # GitHub workflow files, at the repository root
```

## Gates

| Hook | What runs |
| --- | --- |
| `commit-msg` | commitlint: conventional format, known scope, issue reference required |
| `pre-commit` | ktlint format and restage, detekt, ESLint and Prettier on staged files, actionlint on workflows, `ontologyDriftCheck` when the registry is staged, the website drift check when a documentation source is staged, and the guards below |
| `pre-push` | the branch guard, `./gradlew check`, the frontend verify chain, and a check that `contracts/pacts/` matches what the consumer tests just regenerated |

### Guards

Four `pre-commit` jobs are not linters: they refuse a commit rather than report on it, because what
they catch cannot be fixed by a later commit ([ADR-0007](adr/0007-commit-guards.md)).

| Guard | Refuses | Way out |
| --- | --- | --- |
| `protected-branch` | committing while `main` or `master` is checked out, and any push that would write one | `ALLOW_MAIN=1`, or `PROTECTED_BRANCHES` to change the list |
| `hygiene` | conflict markers, files over 512 KiB, credential files (`.env`, keys, keystores) | `HYGIENE_MAX_BYTES`, `.hygieneignore` |
| `secrets` | secretlint's recommended ruleset: tokens, cloud keys, private keys, basic auth in URLs | `.secretlintignore` |
| `lefthook-config` | a `lefthook.yml` that no longer parses | — |

A leaked credential has to be rotated, a large file cannot be removed without rewriting history, and
a direct push to `main` skips review and trips the push-triggered CI run on the default branch. Each
way out is a named, visible decision, unlike `LEFTHOOK=0`, which turns off every gate at once.

Run them over the whole repository without committing:

```bash
npm run guards            # hygiene over every tracked file, secretlint over the working tree, lefthook validate
node scripts/guards.mjs hygiene   # or secrets, or config, on its own
npm run test:unit         # the guards' own tests
```

That is also a CI job, because a guard that lived only in a hook would be the one check `LEFTHOOK=0`
disables permanently.

### Why tests do not run on commit

Note what `pre-commit` does not do: run tests. That is deliberate. This workflow requires committing
a failing acceptance test before the code that satisfies it, so a hook that ran tests on commit
would make the required process impossible without bypassing it, and a bypass that becomes routine
is not a gate.

Tests run on push instead, against the state actually being shared. Intermediate red commits inside
a branch are expected and fine, as long as the tip of the branch is green.

CI re-runs all of it and adds what no hook runs: the browser end-to-end job, the website job
(generator tests, drift, build, site tests) and `format:check`, because a hook can be skipped and a
CI check cannot. The one hook with no CI counterpart is `protected-branch`; CI cannot stop a direct
push to `main`, it can only turn red afterwards.

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
Pact broker is needed (see [ADR-0004](adr/0004-pact-folder-no-broker.md)).

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
