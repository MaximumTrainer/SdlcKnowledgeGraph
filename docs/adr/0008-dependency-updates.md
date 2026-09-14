# ADR-0008: Dependency updates arrive continuously, and the toolchain tracks supported majors

## Status

Accepted. Implemented by [#68](../../../issues/68).

## Context

Enabling Dependabot alerts on this repository reported 48 vulnerabilities at once — 19 high, 28
moderate, 1 low. None of them were new. They had accumulated over the life of the project, in
`frontend/package-lock.json` (44) and `website/package-lock.json` (4), and nothing had ever been
watching for them.

A backlog that size is its own problem. Nobody triages forty advisories in one sitting, so the pile
grows until the count stops carrying information and becomes wallpaper.

The technical shape of the backlog mattered more than its size. Every unfixed advisory traced to a
build-time dependency — `vite`, `esbuild`, `postcss`, `nanoid`, `vitest` — and the fixes were only
published on **supported major lines**:

| Advisory | Vulnerable | First patched |
| --- | --- | --- |
| [GHSA-fx2h-pf6j-xcff](https://github.com/advisories/GHSA-fx2h-pf6j-xcff) | `vite <= 6.4.2` | `vite 6.4.3`, `7.3.5`, `8.0.16` |
| [GHSA-4w7w-66w2-5vf9](https://github.com/advisories/GHSA-4w7w-66w2-5vf9) | `vite <= 6.4.1` | `vite 6.4.2`, `7.3.2`, `8.0.5` |
| [GHSA-67mh-4wv8-2f99](https://github.com/advisories/GHSA-67mh-4wv8-2f99) | `esbuild <= 0.24.2` | `esbuild 0.25.0` |

There is no `vite 5.x` fix, and there will not be one: the line is end of life. The repository was on
`vite 5.4.21`. Staying on a major after it stops receiving security fixes is a decision to accumulate
unfixable advisories, whether or not it is made deliberately.

"It is only a dev dependency" is not an answer on its own. The dev toolchain builds what gets
published, so a compromised build step compromises the artefact.

## Decision

**Stay on majors that still receive security fixes.** `frontend` moved to `vite 7`, `vitest 4`,
`@vitejs/plugin-vue 6` and `@pact-foundation/pact 17`; `axios` to `1.20.0`. All four manifests —
root, `frontend`, `website`, `e2e` — now report `found 0 vulnerabilities`. Every one of the 48 alerts
is resolved by a version bump. None was dismissed.

**Force the patched transitive version when a wrapper pins an end-of-life one.** `vitepress 1.6.4`
depends on `vite ^5.4.14`, which cannot be satisfied by any non-vulnerable release, so
`website/package.json` carries an `overrides` block pinning `vite` and `esbuild` to patched majors.
This deliberately violates the range VitePress declares. It is recorded here because it is the kind
of thing that looks like a mistake later, and because it has to be revisited whenever VitePress is
upgraded — VitePress 2 depends on Vite 7 itself, and the override should be deleted rather than
carried forward once that is stable.

**Update continuously rather than in a backlog.** `.github/dependabot.yml` covers npm at the root,
`frontend/`, `website/` and `e2e/`, plus Gradle for the backend and the GitHub Actions in the
workflows. Minor and patch updates are grouped into one pull request per ecosystem per week; majors
arrive individually, because those are the ones that need a person to read a changelog.

**A major bump is evidence, not a flag.** `npm audit fix --force` is not a fix — it majors
dependencies silently and reports success. Every bump here was verified by running the suites it
could break: lint, typecheck, 47 unit tests, 15 contract tests, the frontend build, the website's
drift check, its 22 unit tests, its build and its 8 browser tests, and the backend's `contractTest`
against the regenerated pact.

## Consequences

The published site is built by a Vite that VitePress does not claim to support. The build, the
generated pages and the browser tests all pass, so the risk is a future VitePress release breaking
against the pinned Vite rather than anything failing silently now — and the drift check would catch a
page that stopped being generated correctly.

`@pact-foundation/pact 17` rewrote the committed pact: twelve empty `"header": {}` and `"status": {}`
matching-rule buckets are no longer emitted, and the metadata records the new library version. No
request, response or matching rule changed, and the backend's `contractTest` verifies against the
regenerated document unchanged ([ADR-0004](0004-pact-folder-no-broker.md) explains why that document
is committed).

Dependabot will now open pull requests that nobody asked for, every week. That is the intended cost.
They go through the same gate as any other change — a branch, a pull request, green CI — and
`commit-message.prefix` is set so their subjects satisfy `commitlint`'s `type-enum` and `scope-enum`.

That was not sufficient on its own. `commitlint.config.cjs` also requires an issue reference, which
Dependabot cannot supply because it has no issue, so the first eleven pull requests this
configuration opened all failed the commit message check and none could be merged — rebuilding
the backlog this ADR exists to prevent, with each item now also showing a red build. #119 added an
`ignores` predicate keyed on the `Signed-off-by: dependabot[bot]` trailer. Keyed on the signature
rather than the subject on purpose: a person can write a subject that looks like a dependency bump,
and only Dependabot can sign as Dependabot, so the exemption cannot be reached by hand.

Some majors cannot be taken, and those are ignored with a reason rather than left to re-propose
themselves every week. Three were blocked in the first batch (#125): TypeScript 7, because
`typescript-eslint` caps the peer range below 6.1; Kotlin beyond 2.0.x, because the latest released
detekt is built against 2.0.21 and fails the `:detekt` task otherwise; and springdoc 3, because it
targets Spring Boot 4 and so is a framework migration wearing a dependency bump's clothes.

Each `ignore` names the blocker and what would lift it, and each ignores the **major** only, so
patches to the line we are on still arrive. An ignore is a decision with an expiry rather than a pin:
the unblock conditions are reviewed whenever one of those pull requests would otherwise have
appeared — in practice, when the ecosystem moves and the entry stops being necessary. Anything still
ignored a year from now is a question to ask, not an answer to keep.

The count means something again. A non-zero alert count is now a signal rather than a backlog.
