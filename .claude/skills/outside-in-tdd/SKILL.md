---
name: outside-in-tdd
description: Implement a roadmap issue outside-in, as this repo requires - red acceptance commit, red contract/API commit, red unit tests, then the green implementation. Use whenever starting work on an issue number, writing a new feature or fixing a bug in this repository.
---

# Outside-in TDD for a roadmap issue

`docs/ROADMAP.md` says a reviewer who cannot see a red-then-green sequence should send the pull
request back. The commit history is the evidence, so the order below is not a suggestion.

## Before writing anything

1. Read the issue: `gh issue view <number>`. It carries the Gherkin, the functional requirements and
   its own outside-in TDD plan. Follow that plan; it names the files to create.
2. Branch from an up-to-date `main`: `git checkout main && git pull && git checkout -b feat/<number>-<slug>`.
3. Read `agents.md` for the architecture rules (hexagonal, ports and adapters, DI in the composition
   root) and `docs/TESTING.md` for which suite a test belongs in.

## The sequence

Each step is its own commit, and the message carries the issue reference (`commitlint` enforces it):

1. **Red acceptance.** Translate the issue's Gherkin into
   `backend/src/acceptanceTest/resources/features/<name>.feature` plus steps under
   `.../acceptance/steps/`. Run it, watch it fail for the right reason, commit:
   `test(backend): add red acceptance test for <thing> (#<number>)`
2. **Red contract or API test.** The REST test, the GraphQL test or the Pact contract, whichever the
   issue names. Commit: `test(backend): add red API test for <thing> (#<number>)`
3. **Red unit tests.** Domain rules as plain unit tests, use cases with faked ports. These may share
   the commit with step 4 only if the issue's plan says so; otherwise commit them red too.
4. **Green.** Write the implementation until every suite passes, then commit:
   `feat(backend): <what now works> (#<number>)`

Committing red is expected and the hooks allow it: `pre-commit` runs formatting and static analysis
only, never tests, precisely so this workflow is possible without `--no-verify`. Never reach for
`--no-verify`; if a hook fails, the hook is right.

## What "red for the right reason" means

A test that fails because a step definition is undefined, a file is missing or the code does not
compile is red for the right reason. A test that fails because of a typo in the expectation is not:
fix the test, not the assertion, before committing it.

## Before you call it done

`agents.md` defines done as: boundaries respected, dependencies wired through DI, tests added and
passing, security considered with negative tests where the path is sensitive, and markdown docs
updated when architecture or behaviour changes. Run the full gate with the `verify-gate` skill
before pushing, and land it with the `land-pr` skill.
