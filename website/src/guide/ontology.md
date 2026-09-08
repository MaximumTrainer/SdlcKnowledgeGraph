<!-- GENERATED FROM docs/ONTOLOGY.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Ontology

The ontology is the contract for what may exist in the graph. It names the entity types, the
relationship types, which types a relationship may connect, and what must be recorded about where
each fact came from.

The registry, provenance envelope and identity rules are implemented ([#18](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/18)) and
served from `GET /api/v1/ontology`. The store that enforces them ([#19](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/19)) and the
code generation that removes the remaining duplication ([#20](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/20)) are still to come, so
persistence currently still uses the per-type Neo4j classes and hand-written Cypher.

The registry lives in `backend/src/main/resources/ontology/v1/`: `nodes.yaml`, `edges.yaml` and
`version.yaml`. It is loaded once at startup, validated on construction, and immutable thereafter.

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

The drift check is directional, which is what lets the registry describe the target model before the
Kotlin classes have caught up. Every **required** registry property must exist on the class, and
every class property must be declared in the registry; an optional registry property may be absent
from the class. So `Repository` already declares `host`, `org` and `name` as its identity while the
class still carries the older `orgRepo`, and #19 finishes that migration without the registry having
to misdescribe the model in the meantime.

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
to assert. See [ADAPTERS.md](/guide/adapters).

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

## Maintaining nodes by hand

`/api/v1/nodes/{type}` is one CRUD surface for every type the registry declares, so a type added to
`nodes.yaml` becomes maintainable without a new endpoint or a new screen. The type in the path is
looked up in the registry and refused if absent; it is never interpolated into a query, so the only
labels that reach Cypher are declared ones.

| Request | Result |
| --- | --- |
| `POST /api/v1/nodes/{type}` with `{props}` | `201` with the derived identity, and a `Location` |
| `GET /api/v1/nodes/{type}?limit=&cursor=` | `200 {items, nextCursor}`, in key order |
| `GET /api/v1/nodes/{type}/{key}` | `200` or `404` |
| `PUT /api/v1/nodes/{type}/{key}` with `{props}` | `200`, same id and same key |
| `DELETE /api/v1/nodes/{type}/{key}?cascade=` | `204`, or `409` while edges remain |

The segment after the type is the derived key, which contains slashes for most types
(`/api/v1/nodes/Repository/github.com/acme/payments`). A full `Type:key` id is accepted there too.

### What is validated, and what it says

Validation is read from the registry rather than written per type. Every problem is reported at
once, each against its own field, so a form can show a user all of them rather than one per round
trip.

| Problem | Response |
| --- | --- |
| Type not in the registry | `404 {error: "unknown node type", type}` |
| Required property missing or blank | `400 {errors: [{field, message: "<name> is required"}]}` |
| Value of the wrong type | `400` with `message: "expected int"` (or the declared wire name) |
| Property not declared | `400` with `message: "not in ontology"` |
| Derived key already held | `409 {error: "node exists", existingId}` |
| Update derives a different key | `409 {error: "identity properties are immutable", fields}` |
| Delete while edges remain | `409 {error: "node has edges", edgeCount}` |

A property the registry does not declare is refused rather than stored and ignored. Storing it would
turn a typo into a silent data-quality problem and would let a caller choose its own keys.

`id` and `key` are refused for the same reason: identity is derived from the properties, so a client
cannot claim one. That is what makes re-stating the same fact idempotent instead of duplicating it.

### Why identity cannot be edited

Because the key is derived, changing an identity property does not rename a node — it describes a
different thing. An update whose properties derive to a different key is therefore refused with the
properties responsible, and the editing form disables them rather than letting a user discover this
on save. Creating that other thing is a create.

The refusal names the properties by putting each changed one back on its own and asking whether the
key returns, rather than from a table of which properties feed which type's identity. A resolver that
starts consulting a different property stays correctly reported.

## Versioning

The registry declares a `version`, and an `Ontology` node records which version the graph was built
with. Adding a type or an optional property is free. Renaming or removing anything needs a migration
script under `ontology/migrations/` and a version bump. `GET /api/v1/ontology` returns the current
registry as JSON, which is what drives the generic editing screen in the frontend.

## Regenerating types

The registry is the only place a node type is declared. Everything else that needs to know the shape
of a node is generated from it:

| Generated file | Consumer |
| --- | --- |
| `backend/src/main/resources/graphql/schema.generated.graphqls` | GraphQL types, one `<Type>Node` per registry type, all implementing `GraphNode` |
| `frontend/src/generated/ontology.ts` | Frontend interfaces, `NODE_TYPES`, `EDGE_TYPES`, `ONTOLOGY_VERSION` |
| `backend/src/main/resources/ontology/v1/ontology.json` | The exact payload `GET /api/v1/ontology` returns, usable as a test fixture |

After changing anything under `ontology/v1/`:

```bash
cd backend && ./gradlew generateOntology
```

Then commit the regenerated files with the registry change. They are committed rather than built
into `build/` on purpose: a reviewer should see the whole effect of an ontology change in one diff.

That only holds if the two cannot be committed apart, so `./gradlew ontologyDriftCheck` regenerates
in memory and fails with `Ontology outputs are stale: <paths>` when a committed file disagrees. It
runs in two places:

- the lefthook `pre-commit` hook, whenever a file under `ontology/` is staged;
- `./gradlew check`, which depends on it, so the `pre-push` hook and the CI backend job both run it.

Queries and mutations stay hand-written in `schema.graphqls`. Generated GraphQL types carry a `Node`
suffix so they can be introduced alongside the hand-written query types without a name collision.

`frontend/src/services/api.ts` re-exports the generated shapes rather than declaring its own, and an
ESLint `no-restricted-syntax` rule refuses a hand-written `interface Repository` (or any other
registry type) outside `src/generated/`. That rule is what stops the model quietly acquiring a
second definition again.
