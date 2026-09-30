# Deployment contract

This application runs in more than one place: the dogfood instance this project runs on fly.io
(`fly/README.md`), and wherever anyone else puts it. This page is the set of
requirements every one of those deployments has to meet, numbered so that a failure can name the
rule it broke.

The contract is executable. `e2e/conformance` checks it against any deployed base URL, from outside,
the way a user reaches the deployment. The fly.io instance is one implementation of this contract,
not its definition. A deployment on another platform has the same suite to pass.

## Requirements

| Id | Requirement | Proved by |
| --- | --- | --- |
| D1 | The liveness and readiness probes answer 200 when the deployment is serving | `D1 the liveness and readiness probes answer 200 UP` |
| D2 | `/actuator/info` reports the commit, the application version, the ontology version, the active profile and whether the deployment is read-only | `D2 /actuator/info says what the deployment is running` |
| D3 | The running image is the one built from the commit being deployed, deployed by digest; no floating tag is deployed | `D3 the deployment is running the commit that was meant to be deployed`, plus the deploy workflow (digest) |
| D4 | A deployment without an authentication provider runs read-only | `D4 a deployment without authentication runs read-only` |
| D5 | In read-only mode every write and every GraphQL mutation is refused with 403, deny-by-default | `D5 a POST/PUT/PATCH/DELETE under /api/v1 is refused`, `D5 a GraphQL mutation is refused` |
| D6 | The ingest endpoints (deployment and seed) still require their bearer token in read-only mode | `D6 the ingest endpoints still require their token` |
| D7 | The graph database, and the API behind the web interface, are not reachable from the public internet | the deploy workflow: `fly/verify.sh` lists the apps' addresses |
| D8 | Secrets come from the platform's secret store; no committed configuration file contains one | the `config-secrets` guard, in `pre-commit` and CI ([Testing](TESTING.md#guards)) |
| D9 | `/actuator/prometheus` and the rest of the actuator are not reachable from the public internet | `D9 the metrics and the rest of the actuator are not public` |
| D10 | Alerts reach a configured receiver | `deploy-check` in the monitoring image (`ops/monitoring`), run by the deploy workflow inside the dogfood instance's monitoring machine, and by the CI end-to-end job against a stand-in receiver |
| D11 | An error response never contains a stack trace, a Cypher fragment or a configuration value | `D11 an error response gives nothing away about the internals` |
| D12 | The deployment tracks `main`: every green CI run on `main` is deployed, and no red one is | the deploy-on-green gate (`.github/workflows/deploy-on-green.yml`, `scripts/deploy-gate.mjs`), which every deploy workflow calls from its `workflow_run` on CI: it proceeds only on `success` for a `push` to `main`, and hands back that run's own commit to deploy |

## What a client can see, and what it cannot

D1 to D6, D9 and D11 are about what the deployment answers, so the suite asks it. D7, D8, D10 and
D12 are about how the deployment was made: a client cannot tell whether a database has a public
address it was never given, or whether a red run was skipped. Those are asserted where the
deployment is made, by the workflow that makes it, and the table names where.

Only three actuator endpoints are public, on purpose: the two probes (D1) and `/actuator/info` (D2).
The web interface proxies exactly those three and nothing else under `/actuator`.

## Read-only is a posture, not access control

A deployment without an identity provider runs the API's development bypass (`AUTH_DISABLED=true`,
[#114](../../issues/114)), which lets every caller in as `anonymous`, so D4 means every such
deployment reachable by people who must not change it also runs with `SDLC_READ_ONLY=true`. The
dogfood instance is one (`fly/fly.backend.toml`). The bypass is refused at startup under the `prod`
profile, and logs the security event `auth.disabled` on every start. A deployment with an identity
provider sets `AUTH_ISSUER_URI` instead, and needs no bypass. The [user guide](USER-GUIDE.md#read-only-instances)
describes what that refuses. It narrows what has to be made safe; it does not make anything safe
that it lets through.

## Running the suite

```bash
cd e2e
npm ci
CONFORMANCE_BASE_URL=https://sdlc-graph.fly.dev npx playwright test --config=conformance.config.ts

# and, to check D3, the commit you expect to be running:
EXPECTED_COMMIT=$(git rev-parse origin/main) CONFORMANCE_BASE_URL=... npx playwright test --config=conformance.config.ts
```

It needs no browser. Every test title starts with its requirement id, so a failure reads as, for
example, `D5 a POST under /api/v1 is refused`. That points straight to the row above.

CI runs it twice against the compose stack. The first run is against the writable stack the browser
tests use, where D5 has to fail, so a suite that has stopped checking anything is caught. The
second run is after restarting the API read-only, where every requirement has to pass. The dogfood
deploy runs it against the live instance after each deploy, and a failure turns the deploy red.

## Deploying on green

A deployment on any platform follows D12 by calling the same gate, rather than restating the rule:

```yaml
on:
  workflow_run:
    workflows: [CI]
    types: [completed]

jobs:
  gate:
    uses: ./.github/workflows/deploy-on-green.yml
    with:
      environment: staging        # for the log
      image_tag_prefix: ""        # optional; image_tag is this followed by the sha
  deploy:
    needs: gate
    if: needs.gate.outputs.should_deploy == 'true'
    env:
      SHA: ${{ needs.gate.outputs.sha }}   # the commit CI tested; check out and build this
```

The gate lets through only a green CI run of a push to `main`, and its `sha` is that run's own
commit, never whatever `main` points at by the time the deploy starts. A deploy job then runs the
conformance suite against what it deployed (FR10). `scripts/deploy-workflows.test.mjs` fails if a
workflow that deploys does not call the gate, or reads the commit from the event itself.

## Released images

Pushing a version tag (`v1.2.0`) runs the whole of CI on the tagged commit and then publishes
`ghcr.io/maximumtrainer/sdlc-graph-backend` and `ghcr.io/maximumtrainer/sdlc-graph-frontend`, each
tagged with the version and the full commit SHA (`release.yml`, in `.github/workflows`). Each image
has an SPDX SBOM, kept with the workflow run, and a signed build provenance attestation pushed
next to it:

```bash
gh attestation verify oci://ghcr.io/maximumtrainer/sdlc-graph-backend:v1.2.0 --owner MaximumTrainer
```

A deployment elsewhere should run one of these by digest, which is what D3 asks. The dogfood
instance does not use them: it builds and deploys every green commit on `main` itself.

## Adding a requirement

A new requirement is a new `D` number, a test whose title starts with it, and a row in the table
above, in that order. The number goes on the end: numbers are never reused or renumbered, because
old failures and issues refer to them.
