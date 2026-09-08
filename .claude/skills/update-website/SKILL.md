---
name: update-website
description: Keep the project website in step with the repository - regenerate the pages from their sources, check for drift, and confirm the publish after a merge. Use when changing docs, ADRs, the ontology or the API, or when asked why the website is stale or how it is published.
---

# Update the website

Nothing under `website/src/` is written by hand. Every page is rendered from a source in this
repository by `website/scripts/generate.mjs`, committed, and held to that source by a drift check
that `pre-commit` and CI both run. A website that restates the ontology is exactly the drift this
project exists to prevent, so the site is derived rather than maintained.

If you find yourself editing a file under `website/src/`, stop: edit its source instead.

## Which source becomes which page

| Source | Page |
| --- | --- |
| `README.md` | `/` |
| `docs/ONTOLOGY.md`, `docs/ADAPTERS.md`, `docs/TESTING.md` | `/guide/<name>` |
| `docs/ROADMAP.md` | `/reference/roadmap` |
| `docs/adr/*.md` | `/adr/<file>`, plus a generated `/adr/` index |
| `backend/src/main/resources/ontology/v1/ontology.json` | `/reference/ontology` |
| `docs/api/openapi.json` | `/reference/api` |

The mapping lives in `GUIDE_PAGES` in `generate.mjs`. Adding a document under `docs/` means adding a
line there and a sidebar entry in `website/.vitepress/config.ts`; nothing else.

## The loop

```bash
cd website
npm ci                 # first time only
npm run generate       # rewrite src/ from the sources
npm run verify         # drift, unit tests, build, browser tests
```

`npm run drift` is what the hook runs. It fails in two directions: a page that is out of date with
its source, and a page under `src/` that nothing generates.

## When the API changed

`/reference/api` comes from `docs/api/openapi.json`, which is exported from the running application,
not written. After adding or changing an endpoint:

```bash
cd backend
./gradlew integrationTest --tests "*OpenApiExportTest*" -DupdateOpenApi=true
```

Then regenerate the site. `./gradlew check` fails if that document drifts from what `/api-docs`
serves, so this is not optional.

## What must not go into a generated page

The drift check only works because every generated file is a pure function of its sources. The commit
SHA and the build time are therefore read at build time in `.vitepress/config.ts` and never written
into `src/`. Putting a timestamp in a generated page would make the check fail on every run.

## Publication

`.github/workflows/website.yml` publishes to GitHub Pages, triggered by the **CI run**, not by the
push. It runs only when that run concluded `success` on `main`, and it downloads the artefact CI
built rather than rebuilding, so what is published is what was tested.

After a merge, confirm it:

```bash
gh run list --workflow=website.yml --branch main --limit 1
```

A red CI run on `main` publishes nothing, and the previous site stays up. That is the intended
behaviour, not a failure to investigate.
