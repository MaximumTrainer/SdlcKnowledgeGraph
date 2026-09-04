# Ontology

The ontology is the contract for what may exist in the graph. It names the entity types, the
relationship types, which types a relationship may connect, and what must be recorded about where
each fact came from.

This document describes the target design. It is implemented by [#18](../../issues/18) (registry,
provenance, identity keys), [#19](../../issues/19) (the store that enforces it) and
[#20](../../issues/20) (code generation). Until those land, the graph still uses hand-written Kotlin
classes and hand-written Cypher.

## Why a registry rather than more classes

Today one entity type is spelled out five times: a domain data class, a Neo4j node class, a REST
DTO, a block of GraphQL schema, and a TypeScript interface. Adding a type means touching all five and
writing new Cypher, which is why half the declared model cannot currently be created through the API
at all.

The registry inverts that. A YAML file is the source of truth:

```yaml
# backend/src/main/resources/ontology/v1/nodes.yaml
version: 1
nodes:
  Repository:
    identity: [host, org, name]
    sensitivity: internal
    properties:
      host:          { type: string, required: true }
      org:           { type: string, required: true }
      name:          { type: string, required: true }
      url:           { type: string }
      defaultBranch: { type: string, default: main }
      topics:        { type: string[] }
      codeowners:    { type: string[] }
      language:      { type: string }
```

The nine core types keep typed Kotlin classes, because the traversal code is hand-written and
benefits from compile-time safety. A startup check compares each class against the registry and
fails fast if they drift. Everything a connector introduces later is a `GenericNode(type, key,
props, provenance)`, so a new source system does not require a new Kotlin class. The GraphQL schema
and the frontend TypeScript types are generated from the registry, which removes three of the five
copies.

## Core entity types

The Harness guidance is to keep the first graph under ten entity types and aimed at a specific
question. These nine are what the two target questions need:

| Type | What it represents |
| --- | --- |
| Repository | A git repository, the anchor for most edges |
| Team | A group that owns something |
| Service | A running logical component, which may span repositories |
| Pipeline | A CI/CD workflow definition |
| Artifact | A built, addressable output such as a container image |
| Deployment | One event of putting an artifact into an environment |
| Environment | A deployment target such as staging or production |
| CloudResource | An infrastructure object in AWS, Azure or GCP |
| ConfigurationItem | A CI from a service-management system such as ServiceNow |

`SyncRun` and `Ontology` also exist as nodes, but they describe the graph itself rather than the
software, so they do not count against the nine.

Person, Policy, Incident, ChangeRequest and Requirement arrive with the M2 and M3 connectors that
can actually populate them. They are registry additions, not code changes.

## Relationship types

Every relationship declares the types it may connect and the name of its inverse. The inverse is a
traversal concept, not a second stored edge.

| Type | From | To | Inverse |
| --- | --- | --- | --- |
| OWNED_BY | Repository, Service, CloudResource | Team | OWNS |
| OWNS_RESOURCE | Repository, Service | CloudResource | RESOURCE_OWNED_BY |
| DEPENDS_ON | Repository, Service | Repository, Service | DEPENDED_ON_BY |
| HAS_PIPELINE | Repository | Pipeline | PIPELINE_OF |
| BUILT_FROM | Artifact | Repository | PRODUCES |
| DEPLOYED_TO | Artifact | Deployment | DEPLOYS |
| TO_ENVIRONMENT | Deployment | Environment | HAS_DEPLOYMENT |
| RELATES_TO_CI | Repository, Service | ConfigurationItem | CI_FOR |
| CANDIDATE_LINK | CloudResource | Repository | CANDIDATE_FOR |

`DEPENDS_ON` carries `kind` (`library`, `api`, `event` or `data`) and `manifest`, the file the
dependency was read from, so an inferred dependency can be traced back to its evidence.

`CANDIDATE_LINK` is how the link resolution engine proposes a connection it is not confident enough
to assert. See [ADAPTERS.md](ADAPTERS.md).

## Provenance

Lasnoski's argument is that a fact without an origin cannot be trusted or corrected. Every node and
every edge carries the same envelope:

```kotlin
data class Provenance(
    val sourceSystem: String,   // "github", "servicenow:prod", "aws:123456789012", "manual"
    val sourceId: String?,      // the identifier in that system
    val ingestedAt: Instant,
    val observedAt: Instant?,   // when the source says it was true
    val confidence: Double,     // 1.0 means reported by the system of record
    val inferred: Boolean,      // true when a rule produced it rather than a system reporting it
    val validFrom: Instant,
    val validTo: Instant? = null,   // null means current
    val syncRunId: String?,
)
```

Neo4j does not store nested maps, so these are flattened to `prov_` prefixed properties. When more
than one source reports the same node, `sourceSystems` accumulates and `confidence` takes the
highest value.

The user interface draws inferred edges differently from asserted ones, because a tag-matched guess
and a human statement should not look identical.

## Identity

A node's key is derived from its properties, never randomly generated. The same real-world thing
seen by two different connectors has to land on one node.

| Type | Key |
| --- | --- |
| Repository | `host/org/name`, lowercased, from any URL form |
| CloudResource | `aws:<arn>`, `azure:<resource id>`, `gcp:<asset name>` |
| ConfigurationItem | `servicenow:<instance>:<sys_id>` |
| Pipeline | `<provider>:<repoKey>:<workflow path>` |
| Artifact | `<registry>/<name>@<digest>`, falling back to `name:version` |
| Environment | normalised name, with an alias table so `prod` and `production` agree |
| Team | `<source>:<slug>` |

So `https://github.com/Acme/Payments.git`, `git@github.com:acme/payments.git` and `acme/payments`
all resolve to `github.com/acme/payments`. A uniqueness constraint on `(label, key)` is created for
every registry type at startup, and nodes carry an `aliases` list when identities are merged.

## Versioning

The registry declares a `version`, and an `Ontology` node records which version the graph was built
with. Adding a type or an optional property is free. Renaming or removing anything needs a migration
script under `ontology/migrations/` and a version bump. `GET /api/v1/ontology` returns the current
registry as JSON, which is what drives the generic editing screen in the frontend.
