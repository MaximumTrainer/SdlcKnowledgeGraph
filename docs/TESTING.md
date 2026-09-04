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
| `contractTest` | `backend/src/contractTest` | no | pre-push, CI |

`backend/src/testSupport/kotlin` holds helpers shared by more than one suite, currently the
Testcontainers Neo4j configuration. It is compiled into both `integrationTest` and `acceptanceTest`.

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
npm run verify              # lint, typecheck, unit tests, build

cd e2e
npx playwright test         # browser tests against the compose stack
```

## Gates

| Hook | What runs |
| --- | --- |
| `commit-msg` | commitlint: conventional format, known scope, issue reference required |
| `pre-commit` | ktlint format and restage, detekt, backend unit tests, ESLint and Prettier on staged files, Vitest for related files |
| `pre-push` | `./gradlew check` and the frontend verify chain |

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
Pact broker is needed.

If the frontend changes what it expects, the pact file changes, and the backend suite fails until it
complies. That is the point.

The suite currently tolerates having no matching tests, because it still contains a placeholder.
[#17](../../issues/17) replaces the placeholder with real verification and removes that tolerance.

## Writing a good acceptance test

Drive the application from outside, through HTTP or the browser. Do not reach into Spring beans to
set up state that a user would have to create through the API.

Assert on what the user asked for. `Then the response status is 404 and the body names the missing
node` is a requirement. `Then upsertEdge throws NodeNotFoundException` is an implementation detail
that will make the test fail the next time someone refactors correctly.

Keep step definitions thin. They translate Gherkin into HTTP calls and assertions, nothing more.
