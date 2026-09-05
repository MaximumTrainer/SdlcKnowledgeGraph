Closes #<!-- issue number -->

## What

<!-- One or two sentences: what changes and why. Link the ontology/adapter docs you touched. -->

## TDD evidence (outside-in)

Each step is a commit on this branch. Paste the short SHA so reviewers can replay the loop.

| Step | Commit SHA | Test file(s) |
|---|---|---|
| Red acceptance test (Cucumber feature and/or Playwright spec) | `<sha>` | |
| Red API / contract test (`@WebMvcTest`, Pact consumer, MSW-backed component test) | `<sha>` | |
| Unit tests | `<sha>` | |
| Green (production code) | `<sha>` | |

## Checklist

- [ ] `lefthook run pre-push` passed locally (all backend suites, frontend lint/typecheck/unit/build)
- [ ] CI is green (commitlint, backend, frontend, e2e)
- [ ] Docs updated where behaviour changed: `docs/ONTOLOGY.md`, `docs/ADAPTERS.md`, `docs/TESTING.md`, README
- [ ] An ADR was added under `docs/adr/` if this PR makes an architectural decision
- [ ] Generated artefacts (GraphQL SDL, `frontend/src/generated/`) are committed and no ontology type was duplicated by hand
- [ ] Commit messages are conventional and reference the issue (`type(scope): subject (#N)`)
