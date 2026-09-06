---
name: verify-gate
description: Run the same checks CI runs, in the same order, before pushing or opening a PR in this repository - backend Gradle check, frontend verify chain, and the compose plus Playwright end-to-end suite. Use before any push, or when asked whether a change would pass CI.
---

# Run the gate locally

`.github/workflows/ci.yml` has four jobs. Reproduce them locally in this order; each is cheap
relative to the one after it, so stop at the first failure and fix it.

## 1. Commit messages

```bash
npx --no-install commitlint --from origin/main --to HEAD
```

Every message needs a conventional type and an issue reference (`(#20)`). This is the cheapest job
to fail on in CI and the most annoying, because it needs a rebase to fix.

## 2. Backend

```bash
cd backend && ./gradlew check --console=plain
```

`check` runs ktlint, detekt, `ontologyDriftCheck`, and the unit, integration, acceptance and
contract suites. Reports land in `backend/build/reports/`.

**On Windows, Testcontainers needs the Docker Desktop pipe or every integration and acceptance test
fails with "Could not find a valid Docker environment":**

```bash
DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine ./gradlew check --console=plain
```

Check `docker context ls` for the endpoint if that pipe name does not exist. A Docker failure looks
like a wall of failing tests but is not a code failure - read the `Caused by` before believing it.

## 3. Frontend

```bash
cd frontend && npm run verify
```

That is lint, typecheck, unit tests and build. CI additionally runs `npm run format:check`, which
`verify` does not, so run it too:

```bash
cd frontend && npm run format:check
```

## 4. End to end

Only worth running when the change touches the API surface, the UI or the compose stack:

```bash
docker compose up -d --build --wait
cd e2e && npx playwright test
docker compose down -v
```

## Reporting the result

Say which jobs ran and which passed. If Docker was unavailable, say the integration and acceptance
suites did not really run rather than reporting the build as green - CI will run them on Linux, and
a claim of green that CI contradicts is worse than saying it was not checked.
