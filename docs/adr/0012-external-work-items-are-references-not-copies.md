# ADR-0012: External work items are references, not copies

## Status

Accepted. Implemented by [#85](../../../issues/85).

## Context

Change lineage answers two questions: where is this work item live, and what did this deployment
carry. A work item is the task, ticket or issue that asked for a change. It lives in a system this
graph does not own, such as Chorus, Jira, Linear or GitHub Issues, and that system goes on changing
it: the status moves, the assignee changes, the description is rewritten, and the item is closed and
reopened.

The graph could hold those items in one of two ways. It could copy them, with their status,
assignee, description and history, or it could refer to them.

A copy looks convenient because every question could be answered from one place. But a copy is only
as fresh as the last sync. It needs a connector per system that keeps up with every field, and it
lets two sources of truth disagree about the same ticket with nothing to say which is right. The
questions lineage answers do not need a copy anyway. They ask which deployment carried which change,
and a change implements a work item whatever that item's status is today.

Identity matters as much as content. Each owning system writes its identifiers its own way, and only
that system knows which parts of them are case-sensitive. Jira's `PAY-42` and `pay-42` may be one
issue. A Chorus task id or a GitHub URL path may not be.

## Decision

- **An `ExternalWorkItem` is a reference.**
  - It records the `uri` the owning system gives it, the `system` it belongs to, and optionally its
    human-facing `externalKey` (for example `CH-42`) and a `title`, so a list of them is readable.
  - It does not record status, assignee, description or history. Those are read from the owning
    system when needed, by following the URI.
- **Its identity is the URI exactly as written.**
  - The key is the `uri`, trimmed and otherwise untouched. It is not lowercased, parsed or
    normalised, so `…/browse/PAY-42` and `…/browse/pay-42` are two nodes.
  - Folding them would be a guess about another system's rules. When two spellings turn out to be
    one item, merging them is a deliberate act, the same as for any duplicate (see
    [ONTOLOGY.md](../ONTOLOGY.md#identity)).
- **The property is `externalKey`, not `key`.** Every node already has a `key`, its identity, and a
  work item's identity is its URI.
- **Lineage lives on the edges between the graph's own nodes.**
  - A `Change` `IMPLEMENTS` a work item.
  - A `PullRequest` `MERGES` a change.
  - An `Artifact` `CONTAINS` a change.
  - A work item is `TRACKED_IN` a team.
  - Each edge carries provenance like any other, so who claimed that a change implements a ticket
    can be cited and corrected.
- **Nothing is inferred.** A change implements a work item only when some source says so. Nothing is
  guessed from a commit message.
- **An unknown lineage says so.** When no artifact of a deployment contains any change,
  `GET /api/v1/deployments/work-items` answers `lineage: "unknown"`, not an empty list that would
  read as "this deployment carried nothing".

## Consequences

- The graph never disagrees with the tracker about a ticket's state, because it never states one.
- A reader who wants the status follows the URI to the owning system. A future connector that pulls
  status into a context pack does it at read time, not by writing it into the graph.
- A work item's URI holds `//`, which no URL path can carry past the security firewall.
  - Work items are therefore addressed by query parameter: `GET /api/v1/work-items/deployments?uri=`.
  - Any node can be read, replaced and deleted at `/api/v1/nodes/{type}/by-key?key=`.
  - In the web interface, a key holding `//` is encoded as a single route segment.
- The same item written two ways is two nodes until someone merges them. That is the price of never
  guessing another system's identity rules.
- The nodes are written straight into the graph through the node API. The proposal queue that would
  let a person review them first ([#74](../../../issues/74)) does not exist yet.
