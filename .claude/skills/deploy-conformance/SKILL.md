---
name: deploy-conformance
description: Check a deployment of this application against its contract (docs/DEPLOYMENT.md) with the conformance suite, read a failure back to the requirement it breaks, and add a new deployment requirement. Use when asked whether a deployment is safe or current, when a deploy's conformance step fails, or when adding a deployment rule.
---

# Deployment conformance

The contract is `docs/DEPLOYMENT.md`: numbered requirements D1 to D12, each naming what proves it.
`e2e/conformance` is the executable half. It runs against any base URL, from outside, and needs no
browser.

## Run it against a deployment

```bash
cd e2e
npm ci
CONFORMANCE_BASE_URL=<https://the-deployment> npx playwright test --config=conformance.config.ts
```

Set `EXPECTED_COMMIT=<full sha>` to check D3, that the deployment is running the commit you meant to
deploy; without it D3 is skipped, not passed. D6 is skipped until the ingest endpoint (#7) exists.

Against the local stack: `docker compose up -d --build --wait`, then
`SDLC_READ_ONLY=true docker compose up -d --wait backend`, then run the suite with
`CONFORMANCE_BASE_URL=http://localhost:5173`. Against the writable stack D5 fails, which is correct:
a deployment without authentication has to be read-only (D4).

## Read a failure

Every test title starts with its requirement id. `D5 a POST under /api/v1 is refused` failing means
row D5 of `docs/DEPLOYMENT.md` is broken, whatever the assertion message says. Fix the deployment,
not the test. A test that has to be weakened to pass means the requirement is wrong, and that is a
change to the table that has to be reviewed on its own.

Requirements the suite cannot see (D7, D8, D10, D12) are asserted by the deploy workflow and the
guards; the table says where.

## Add a requirement

In this order, one PR:

1. A new row in the table in `docs/DEPLOYMENT.md` with the next unused `D` number. Numbers are never
   reused or renumbered.
2. A test in `e2e/conformance/` whose title starts with that number, committed red against a
   deployment that breaks it.
3. The change that makes deployments meet it.

If a client cannot observe it, say in the table which workflow step or guard asserts it instead.
