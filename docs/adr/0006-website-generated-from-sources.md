# ADR-0006: The website is generated from repository sources

## Status

Accepted. Implemented by [#43](../../../issues/43).

## Context

The only public face of this project was `README.md` and the `docs/` folder, so a person who wanted
to know what the graph models had to clone the repository to find out.

The obvious fix is to write a website. That is also a trap. This project's whole argument is that
facts should be derived from a system of record and carry their provenance, and that a formal
ontology only pays off if everything downstream of the registry is generated from it rather than
restated. A hand-written page describing the ontology would drift the first time a property was
added, and the published model would quietly stop being the real one — the exact failure the product
exists to prevent, on the product's own website.

We already have the mechanism. The ontology's GraphQL SDL and TypeScript types are generated,
committed and held in place by `ontologyDriftCheck` ([ADR-0003](0003-hybrid-ontology-registry.md)).
The pacts are generated, committed and diffed in CI ([ADR-0004](0004-pact-folder-no-broker.md)).

## Decision

Every page of the website is generated from a source in this repository and committed under
`website/src/`. Nothing there is written by hand, and `npm --prefix website run drift` fails in both
directions: a page out of date with its source, and a page nothing generates.

The site is **VitePress**. It reuses the Vue toolchain the frontend already depends on, so no second
framework enters the repository. Astro and Docusaurus were considered and rejected on that basis
alone; all three would have done the job.

The REST reference is rendered from `docs/api/openapi.json`, which is exported from the running
application by an integration test rather than written. That test fails when the committed document
no longer matches what `/api-docs` serves, so the reference cannot describe endpoints that do not
exist.

Internal links are rewritten at generation time. `../../issues/20` and `docs/ROADMAP.md` both resolve
when a file is read in the repository and neither resolves when it is served as a page, so both
forms are translated and external links are left alone. `ignoreDeadLinks` is off, so a link to a page
that does not exist fails the build.

Publication is to **GitHub Pages**, triggered by the CI run rather than by the push, and only when
that run concluded successfully on `main`. The workflow downloads the artefact CI built instead of
rebuilding, so the published bytes are the tested bytes.

The commit SHA and the build time appear in the footer but are read at build time and never written
into a generated page. The drift check only works because everything it compares is a pure function
of the sources; a timestamp in a committed page would make it fail on every run.

## Consequences

A change to the ontology, an ADR or the API shows up in the diff as a changed page, reviewable
alongside the change that caused it. Forgetting to regenerate fails the commit, not the reader.

The website cannot say anything the repository does not. That is the point, and it is also the cost:
marketing copy, a narrative landing page or anything else with no source in the repository has
nowhere to live under this decision. If that becomes wanted, the answer is a hand-written page in a
directory the drift check excludes, declared explicitly — not an exception carved into a generated
one.

Adding a document under `docs/` means adding a line to the generator's mapping and a sidebar entry.
That is a small tax on every new document, paid to keep the sidebar honest.

Publication depends on GitHub Pages being enabled for the repository, which is a repository setting
rather than something the workflow can assume.
