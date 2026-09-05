# ADR-0003: A hybrid ontology, with a registry as the source of truth

## Status

Accepted. Implemented by [#18](../../../issues/18), [#19](../../../issues/19) and
[#20](../../../issues/20).

## Context

The graph needs a declared model: entity types, relationship types, which types each relationship
may connect, and provenance on every fact. That is the central argument of Lasnoski's article on an
enterprise SDLC knowledge graph, and it is what allows a connector to add data without a developer
hand-writing a class for it.

The current code has the opposite property. Each entity type is written out five times: a domain
data class, a Neo4j node class, a REST DTO, a fragment of GraphQL schema, and a TypeScript
interface. Relationship types are string literals inside Cypher. Adding a type means editing all five
places and writing new queries, and the consequence is visible: of the nine declared types, only
Repository can be created through the API, and the frontend form fields are hand-written HTML.

Two clean alternatives were considered.

**Fully generic.** Every node becomes `GraphNode(type, key, props)` with no typed classes at all.
Maximum flexibility, and connectors can introduce types freely. The cost is that the hand-written
traversal code loses all compile-time safety, exactly where the current bugs live. A misspelled
property or relationship name becomes a runtime empty result rather than a compile error.

**Fully typed.** Keep writing Kotlin classes for everything. Compile-time safety everywhere, but
this is the status quo, and it means a ServiceNow connector cannot introduce a type without a code
change and a release.

## Decision

Adopt a hybrid.

A YAML registry under `backend/src/main/resources/ontology/v1/` is the single source of truth for
node types, relationship types, allowed endpoints, inverse names, identity keys and sensitivity.

The nine core entity types keep typed Kotlin classes, because the impact-analysis and link-resolution
code is hand-written and benefits from the compiler. A startup check compares each class against the
registry and fails fast on drift, so the two cannot silently diverge.

Anything a connector introduces beyond those nine is a `GenericNode(type, key, props, provenance)`,
requiring a registry entry but no Kotlin class.

Persistence goes through one generic `GraphStore` over `Neo4jClient` that builds Cypher from registry
metadata, replacing the per-type Spring Data node classes and repositories.

The GraphQL schema and the frontend TypeScript types are generated from the registry by a Gradle
task, with a CI check that fails when the committed output has drifted.

Every node and edge carries the same provenance envelope: source system, source id, timestamps,
confidence, whether it was inferred, and validity window.

## Consequences

Five hand-maintained copies of each type become two: the registry entry and, for core types only, a
Kotlin class checked against it.

A new connector can contribute new entity types by adding registry entries, which is what makes the
adapter story credible rather than aspirational.

The generic editing screen in the frontend reads `GET /api/v1/ontology` at runtime, so a new type
appears in the user interface without a frontend rebuild.

Cypher is constructed from registry-validated labels rather than free strings, which closes the
injection surface that string interpolation would otherwise open. Validation against the registry is
mandatory before any label reaches a query.

The migration is not free. Replacing the Spring Data node classes touches every read path, and it
must happen before the CRUD and relationship work rather than after, which is why the issues are
ordered as they are.

Ontology changes need governance. Additive changes are free; renames and removals require a
migration script and a version bump recorded on an `Ontology` node in the graph.
