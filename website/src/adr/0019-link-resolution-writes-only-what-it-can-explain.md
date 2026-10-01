<!-- GENERATED FROM docs/adr/0019-link-resolution-writes-only-what-it-can-explain.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0019: Link resolution writes only what it can explain, and keeps to a person's decision

## Status

Accepted. Implemented by [#28](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/28).

## Context

A cloud resource rarely says which repository made it. The evidence is spread out. A cloud tag may
name the repository. A pipeline may have deployed an artifact built from it. An IaC file may name
the resource. Or the names may simply match. [#28](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/28) asks for an engine that weighs
this evidence with pluggable rules and a fixed confidence table: manual 1.0, tag 0.95, deployment
0.9, IaC 0.7, naming 0.4. The strongest proposal wins. At or above 0.5 it becomes an inferred
`OWNS_RESOURCE`; below that it becomes a `CANDIDATE_LINK` that a person accepts or rejects.

Re-running the engine must change nothing. A rejection must hold. Each decision must record who made
it and when.

The cloud connectors that will record tags (#25, #26, #27) are not built. The engine therefore reads
only what the graph holds, or what the registry now declares for them to write.

## Decision

- **Evidence lives on the nodes the connectors write, as `key=value` lists.** The registry has no
  map type, so the evidence is stored as lists:
  - `CloudResource.tags` is a list of `key=value` entries. Writing one property per tag would
    declare a property for every tag key anyone uses.
  - `Deployment.targetResourceKeys` names the resources a deployment reached. It is a property,
    not a new edge: the deployment rule needs only the keys, and a pipeline knows the keys before
    the resources exist in the graph.
  - The evidence a rule found is stored the same way, on `OWNS_RESOURCE.evidence` and
    `CANDIDATE_LINK.evidence`. Every link can then say what it rests on.

- **`CANDIDATE_LINK` runs from the resource to the repository, with confidence in provenance.** It
  is a proposal about the resource, so it starts there. Its inverse, `MAY_OWN`, reads well from the
  repository. Its `impact` and `ownership` are `none`, so a guess never widens a blast radius or
  names an owner. Its id is a hash of the resource and repository keys: stable across runs and
  different for every pair. The `status` property is one of:
  - `pending` or `conflict`, while the candidate is open;
  - `rejected` or `superseded`, which are tombstones;
  - `accepted`, once the candidate has been closed into a manual owner.

- **The engine manages only what it wrote.** An owner written by the engine has the source
  `link-engine`. Other owners are treated as follows:
  - A person's owner (`rule: manual`, or the `manual` source) is the manual rule's evidence. The
    engine never overwrites or closes it.
  - An owner stated by another source is left alone, and its repository is not proposed over it.
    That source said so, and the engine has no standing to disagree.

- **A tie owns nothing.** Two proposals equally strong at the top are both conflicts. Choosing
  between them would be a guess presented as a fact. A manual proposal wins every tie and is never
  a candidate.

- **Nothing is deleted.** An owner or candidate that nothing proposes any more is closed with
  `validTo`, so the graph still says what held and when. One that is proposed again is reopened
  with a fresh `validFrom`.

- **A decision is pinned to the evidence it was made on.** Each candidate stores `evidenceHash`, a
  hash of its rule and evidence. While a rejected or superseded candidate's evidence hashes the
  same, its repository is not proposed again. This matches the tombstone convention of ADR-0014 and
  ADR-0015. New evidence reopens the candidate for review, because the question it answered has
  changed.

- **Accepting writes the principal's word.** Accepting a candidate, or stating a link through
  `POST /api/v1/links/manual`, has these effects:
  - It writes a manual `OWNS_RESOURCE` at 1.0, not inferred, with `acceptedBy`, `acceptedAt` and a
    manual provenance naming the writer.
  - It closes every other owner that the engine or a person stated for the resource.
  - It marks the pair's candidate `accepted` and closes it.
  - It supersedes the resource's other open candidates.

  Rejecting records `rejectedBy` and `rejectedAt` and leaves the candidate current, so the rejection
  can be listed.

- **A resolution is a sync run of `link-engine`.** It is recorded RUNNING and then run on a
  single-thread executor, so a request is answered 202 at once and two resolutions never interleave.
  A FULL run covers every resource. A scoped run is INCREMENTAL. It is recorded like a connector's
  run, with metrics under the `link-engine` series. No `ConnectorState` is written, because the
  engine is not a source whose freshness anyone should alert on. Resolutions start in three ways:
  - on request;
  - nightly (`links.schedule`);
  - after a connector run listed in `links.triggers`, scoped to the resources and repositories that
    run produced. `SyncService` announces each finished run as an application event, after
    recording it.

  On a read-only instance, neither the schedule nor the trigger runs.

- **Deciding needs `graph:write`.** The issue mentions admin and curator roles, which the
  application does not have. Scopes are what it enforces (ADR-0005), so reviewing is a write like
  any other. Reading candidates needs `graph:read` and works in the anonymous read-only mode.

## Consequences

- Each rule is a class and a bean. Adding a rule needs no change to the engine, the API or the UI;
  docs/ONTOLOGY.md describes how.
- The tag and deployment rules only produce output once a connector writes `tags` or
  `targetResourceKeys`. Until then, the IaC and naming rules, and people, supply the links.
- Within a single run, an unchanged owner costs one write, its `resolvedAt`, and an unchanged
  candidate costs none.
- Adding the edge took the Markdown rendering of the ontology past its 12,000-character bound. To
  bring it back under, the rendering now omits an example beside an enum's allowed values and an
  instant's example, as docs/ONTOLOGY.md records. The bound itself was not raised.
