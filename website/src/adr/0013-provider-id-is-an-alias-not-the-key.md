<!-- GENERATED FROM docs/adr/0013-provider-id-is-an-alias-not-the-key.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0013: Provider id is an alias, not the key

## Status

Accepted. Implemented by [#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88).

## Context

A Repository is keyed on its remote, `host/org/name`, derived from its `url` in any written form
([#8](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/8)). That is the right identity to show a person: it is what they type, what
the web interface displays, and what every other system that mentions the repository can be reduced
to.

It is the wrong identity to hold on to. A repository on GitHub can be renamed, or transferred to
another organisation, and its remote changes with it. GitHub's numeric repository id does not. Any
system that authenticates as a GitHub App addresses repositories by that id: it mints an
installation token per repository and never needs to know the remote. An agent workspace such as
Chorus is one of them.

Before this change, a repository renamed on GitHub and then written again under its new remote became
a second node. Every edge stayed on the first one: its owners, its pipelines, the artifacts built from
it and their deployments. The graph then answered "no deployments" for a repository that was running
in production.

There were three ways to fix that:

1. **Key the node on the provider id.** A repository with no known id, written by hand or by a
   connector that does not read one, would have no key. The key would stop being derivable from the
   remote, so the same repository written by two sources, one with the id and one without, would
   become two nodes.
2. **Make the provider id part of the key.** This has the same problem, and a rename would still
   change the key.
3. **Keep the key and add the provider id beside it as an alias.** The alias is unique where it is
   present and is consulted before the key.

## Decision

- **The key stays `host/org/name`.** A Repository gains `provider` (`github`, `gitlab` or `other`) and
  `providerId`, both optional. Neither is part of its identity.
- **The registry declares the alias.** `nodes.yaml` says `alias: [provider, providerId]` for
  Repository. The registry refuses an alias that names a property the type does not declare, one that
  is part of its identity, or one that is required, because a repository must be writable before its
  provider id is known.
- **The database enforces it.** At startup a uniqueness constraint is created over the alias's
  properties together, `(provider, providerId)`, for every type that declares one.
  - A node that lacks any part of the alias is outside the constraint.
  - A GitHub id and a GitLab id that happen to be the same number do not collide.
- **The alias is consulted first.**
  - A create whose alias another node holds is refused with `409 {error: "node exists", existingId,
    alias}`.
  - An update that carries the alias the node already holds, with a remote that derives a different
    key, renames the node in place. The update is addressed at the old key, or at the new one when
    nothing holds it yet. The node keeps its edges, and its old key is recorded in the provenance
    envelope's `previousKeys`.
  - A connector's delta does the same.
- **The alias authorises a rename only when the node already holds it.** An update that sets a
  provider id and changes the remote in the same write is refused as a change of identity, as it was
  before. Otherwise any writer could take any node by naming an id. Once a node holds a provider id,
  an update may not replace it with another.
- **The provider comes from the host where the host is one provider.** A provider id sent without a
  provider is `github` on github.com and `gitlab` on gitlab.com. On any other host the provider has to
  be named; half an alias is refused as a validation error.
- **The old key still finds the node.** The repository lookups (`GET /api/v1/repositories?url=` and
  `/by-key`), impact (`POST /api/v1/impact`) and the sha scoping of impact all fall back to
  `previousKeys` when no node holds a key now. A node that holds a key now always wins over one that
  used to hold it.

## Consequences

- A rename seen by any writer that knows the provider id moves the node, and every edge moves with
  it. A writer that does not know the id still gets the old behaviour: a changed remote is refused.
- Nodes that hang off the repository by its key, rather than by an edge, keep the old key:
  - a Pipeline's `repoKey` and a Change's `repositoryKey`, and the keys derived from them;
  - Changes are still found for the renamed repository, because their scoping reads `previousKeys`;
  - a Pipeline re-read from the new remote is a second Pipeline until the two are merged.
- Two nodes that already exist for one repository are not merged. That needs a `merge` proposal in
  the review queue of [#74](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/74), filed when #74 lands.
  - A rename onto a key another node holds is refused with a `409` that names that node.
  - A connector's delta writes the reported node without the alias in that case, so the constraint
    holds and the sync does not fail, and the node that holds the id keeps it.
- `previousKeys` is history, not a statement of the latest write. It is written only by a rename, and
  every other write leaves it where it is. On an edge it is always empty.
- Nothing populates the provider id yet. The GitHub connector will read it
  ([#23](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/23)). Until then it is written through the node API.
- The registry version moves to 1.2.0. Like 1.1.0 it only adds, and a 1.1.0 build refuses to start
  against a graph a 1.2.0 build has recorded.
