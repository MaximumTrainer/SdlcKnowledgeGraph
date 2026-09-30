<!-- GENERATED FROM docs/adr/0011-impact-scoring-versioned-and-deterministic.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# ADR-0011: Impact scoring is versioned and deterministic

## Status

Accepted. Implemented by [#87](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/87).

## Context

`POST /api/v1/impact` answers a coding agent's question before it edits: what does a change to this
repository reach, where does it run, who owns it, and which of it matters most. Its consumer is not
a person scanning a list but a brief builder that fits the answer into a token budget, dropping from
the bottom, and a context pack ([#79](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/79)) that cites each fact by id.

Both break on an answer whose order wanders. If two identical requests list the same hits in a
different order, the budget cuts a different tail, and one task produces two different briefs. The
database does not promise an order for rows it considers equal, and a score computed in floating
point ties often: every hit one hop away and outside any environment scores the same.

A ranking also has to be explainable after the fact. A consumer that cached a brief built from one
formula has no way to tell it apart from one built from the next unless the answer says which
formula it used.

## Decision

- **The score is a published formula with a version.** Version 1 is
  `min(1, 1 / (1 + hops) * tierWeight * pathBoost)`, the tier weights are production 1.0,
  pre-production 0.6, other (and no environment) 0.5 and development 0.3, and the path boost is 2.
  Every answer carries `scoring: {version, formula, tierWeights, pathMatchBoost}`. Any change to the
  formula or its constants is a new version, not an adjustment.
- **The order is total and made in the application.** Score descending, then hops ascending, then
  node id ascending. The id is unique, so no two hits compare equal and nothing is left to the
  database. Each node is explained by one path chosen by a total order too: fewest hops, then most
  confident, then stated before inferred, then the path's spelling.
- **The same graph gives the same bytes.** Properties are serialised in key order and every list in
  the answer - owners, matched paths, tier weights - is sorted, so the body, not only the order of
  hits, is identical between identical requests. An acceptance test compares two responses byte for
  byte, and a unit test ranks 300 hits with deliberately tied scores in fifty shuffled orders.
- **Truncation is honest and uncounted.** At most `limit` hits are returned with `truncated: true`
  when there were more. The number left out is not given: counting it would mean ranking everything
  the walk found to produce a figure the consumer cannot use.
- **What the answer cannot do, it says.** A `paths` filter with no index to read it against answers
  `pathFilter: "not_applied"` and the unfiltered hits, and a `sha` answers `changeScope: "unknown"`
  until Change nodes exist ([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)). Neither pretends to have narrowed anything.

## Consequences

- A consumer can pin a scoring version and notice when it changes, and a brief built twice from one
  graph is the same brief.
- The tier weights encode a judgement - that a production deployment two hops away matters more
  than a service one hop away - which some estates will want tuned. Tuning them is a new scoring
  version, published in the answer, rather than configuration that makes two instances rank
  differently under one version number.
- Environments with no `tier` rank as `other` until someone sets it; the graph does not guess
  production from a name.
- The ranking is only as deterministic as the graph: a sync that changes a fact changes the answer,
  which is the point.
