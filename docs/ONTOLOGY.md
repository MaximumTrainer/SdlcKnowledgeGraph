# Ontology

The ontology is the contract for what may exist in the graph. It names the entity types, the
relationship types, which types a relationship may connect, and what must be recorded about where
each fact came from.

The registry, provenance envelope and identity rules ([#18](../../issues/18)) are served from
`GET /api/v1/ontology`; the registry-driven store that enforces them ([#19](../../issues/19)) and
the code generation that removes the remaining duplication ([#20](../../issues/20)) are both in
place. Persistence goes through one `Neo4jGraphStore` whose Cypher is built from the registry, and
the GraphQL types, the frontend types and `ontology.json` are generated from it.

The registry lives in `backend/src/main/resources/ontology/v1/`: `nodes.yaml`, `edges.yaml` and
`version.yaml`. It is loaded once at startup, validated on construction, and immutable thereafter.

## Why a registry rather than more classes

Before the registry, one entity type was spelled out five times: a domain data class, a Neo4j node
class, a REST DTO, a block of GraphQL schema, and a TypeScript interface. Adding a type meant
touching all five and writing new Cypher, which is why half the declared model could not be created
through the API at all.

The registry inverts that. A YAML file is the source of truth. This is the real shape of an entry in
`nodes.yaml` — properties are a list, each with a `name`, a `type` (`string`, `int`, `boolean`,
`instant` or `string[]`), `required`, an optional `description` and an optional `enum`:

```yaml
# backend/src/main/resources/ontology/v1/nodes.yaml
nodes:
  Repository:
    description: A git repository, the anchor for most of the graph.
    identity: [host, org, name]
    properties:
      - { name: host, type: string, required: false, description: "Host of the remote, e.g. github.com" }
      - { name: url, type: string, required: true, description: "The git remote, in any form" }
      - { name: host, type: string, required: false, description: "Host of the remote. Derived from url" }
      - { name: org, type: string, required: false, description: "Owning organisation. Derived from url" }
      - { name: name, type: string, required: false, description: "Repository name. Derived from url" }
      - { name: defaultBranch, type: string, required: true }
      - { name: topics, type: "string[]", required: true }
      - { name: codeowners, type: "string[]", required: true }
      - { name: language, type: string, required: false }
```

The registry's version lives in `version.yaml` (semver, currently `1.0.0`), not in `nodes.yaml`.
There is no `default` or `sensitivity` key; a property the loader does not recognise fails startup.

### A Repository is identified by its git remote

The identity properties are optional on purpose, and `url` is the required one. A caller supplies the
remote in whatever notation their tool wrote it, and the server derives `host`, `org` and `name`
before anything is validated or stored. Asking a caller for the three parts would push the splitting
out to every caller, and a caller who splits it differently is how one repository becomes two nodes.

Every one of these is the same Repository, `github.com/acme/payments`:

| Written as | Where it comes from |
| --- | --- |
| `https://github.com/acme/payments` | pasted from a browser |
| `https://github.com/Acme/Payments.git` | the clone URL, with GitHub's casing |
| `git@github.com:acme/payments.git` | what `git remote -v` prints |
| `ssh://git@github.com/acme/payments` | the SSH URL form |
| `github.com/acme/payments` | written in prose |
| `acme/payments` | the shorthand, which assumes `github.com` |

Only a trailing `.git` is stripped, so `payments.js` keeps its name. The stored `url` is always the
canonical `https://host/org/name`, whatever was supplied.

One parser decides all of this, and it exists twice: in Kotlin for the API and in TypeScript for the
editing screen, which previews the key before you save. Both are held to one table of forms,
`frontend/src/test/fixtures/git-remotes.json`, because a preview that disagrees with what the server
stores would be worse than showing none. Something that is not a remote is refused as `400 invalid
git remote` with the reason, rather than as three missing properties the caller never sent.

`GET /api/v1/repositories/by-key?key=` normalises the key it is given through the same parser, so a
connector holding `Acme/Payments` finds the node without normalising first.

`defaultBranch`, `topics` and `codeowners` are required because the typed Kotlin class carries them.

Seven of the core types keep typed Kotlin classes (Repository, Team, Pipeline, Artifact, Deployment,
Environment and CloudResource), because the hand-written traversal code benefits from compile-time
safety. A startup check compares each class against the registry and fails fast if they drift.
Service, ConfigurationItem and everything a connector introduces later exist only in the registry
and are handled as generic nodes, so a new type does not require a new Kotlin class.

The drift check is directional. Every **required** registry property must exist on the class, and
every class property must be declared in the registry; an optional registry property may be absent
from the class. That is what lets the registry describe the target identity model while the
classes still carry legacy properties.

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
| OWNS_RESOURCE | Repository, Service | CloudResource | OWNED_BY_REPO |
| DEPENDS_ON | Repository, Service | Repository, Service | DEPENDED_ON_BY |
| HAS_PIPELINE | Repository | Pipeline | PIPELINE_OF |
| RELATES_TO_CI | Repository, Service | ConfigurationItem | CI_OF |
| BUILT_FROM | Artifact | Repository | BUILDS |
| DEPLOYED_TO | Artifact | Deployment | DEPLOYMENT_OF |
| TO_ENVIRONMENT | Deployment | Environment | HOSTS |
| PROVIDES | Repository | Service | PROVIDED_BY |

This table is a copy of `edges.yaml`. The website's
[ontology reference](https://maximumtrainer.github.io/SdlcKnowledgeGraph/reference/ontology) is
rendered from the registry itself, so it is the one to trust if the two ever differ.

Three relationships carry properties of their own. `DEPENDS_ON` requires `kind` (`library`, `api`,
`event` or `data`) and accepts `manifest`, the file the dependency was read from, so an inferred
dependency can be traced back to its evidence. `OWNS_RESOURCE` accepts `rule`, which link rule
proposed it, and `BUILT_FROM` accepts `commitSha`.

A `CANDIDATE_LINK` relationship, for connections the planned link resolution engine is not confident
enough to assert, is described in [ADAPTERS.md](ADAPTERS.md) and will be added to the registry with
that engine ([#28](../../issues/28)).

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

Neo4j does not store nested maps, so these are flattened to `prov_` prefixed properties, and an
index on `prov_sourceSystem` is created for every type. Re-stating a node replaces its provenance
with that of the latest write; merging several sources' provenance on one node (accumulating
`sourceSystems`, keeping the highest `confidence`) is part of the connector work
([#22](../../issues/22)).

Today every write comes from a person, through the interface or the API, so `sourceSystem` is
always `manual`, `confidence` is `1.0`, `inferred` is `false` and `syncRunId` is null. The user
interface shows the envelope on every node's page; drawing inferred edges differently from asserted
ones will matter once a connector writes the first inferred one.

## Identity

A node's key is derived from its properties, never randomly generated. The same real-world thing
seen by two different connectors has to land on one node.

| Type | Key |
| --- | --- |
| Repository | `host/org/name`, lowercased, from `url` in any remote form or from `host`, `org` and `name` |
| CloudResource | `<provider>:<resourceId>`, e.g. `aws:<arn>`, `azure:<resource id>`, `gcp:<asset name>` |
| ConfigurationItem | `<sourceSystem>:<instance>:<sysId>`, e.g. `servicenow:acme:abc123` |
| Pipeline | `<provider>:<repoKey>:<workflowPath>` |
| Artifact | `<registry>/<name>@<digest>`, falling back to `<name>:<version>` |
| Deployment | `<artifactKey>#<environmentKey>#<deployedAt as epoch seconds>` |
| Environment | lowercased name, with an alias table so `prod`, `prd` and `live` all mean `production` |
| Team, Service | lowercased, trimmed `name` |
| Ontology | `version` |
| SyncRun | `id` |

So `https://github.com/Acme/Payments.git`, `git@github.com:acme/payments.git` and `acme/payments`
all resolve to `github.com/acme/payments`. A uniqueness constraint on `key` is created per label for
every registry type at startup. Merging two existing nodes that turn out to be the same thing, with
an `aliases` list recording the keys folded in, is not implemented; today the derivation rules are
what stop the duplicate being created in the first place.

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

### Relating nodes

`/api/v1/edges` is one surface for every relationship the registry declares, validated against it at
both ends.

| Request | Result |
| --- | --- |
| `POST /api/v1/edges` with `{type, fromId, toId, props}` | `201` with the inverse and both ends; `200` when the relationship was already there |
| `DELETE /api/v1/edges?type=&fromId=&toId=` | `204`, or `404` when there is no such edge |
| `GET /api/v1/edges?nodeId=&direction=&edgeType=` | `200 {items}`, each rendered under the name that end sees |

Node ids travel in the body or as query parameters, never as path segments. A derived key contains
slashes, and an encoded slash in a path is refused by the servlet container; enabling it would open a
path-traversal surface for the sake of prettier URLs. That is also why the listing is
`GET /api/v1/edges?nodeId=` rather than `/nodes/{type}/{id}/edges`: the node segment has to be a
greedy capture, and a greedy capture swallows any suffix after it, so the sub-resource path cannot be
expressed at all.

### What is refused, and what it says

| Problem | Response |
| --- | --- |
| Type not in `edges.yaml` | `400 {error: "unknown edge type", type}` |
| Ends the ontology does not permit | `400 {error: "edge not allowed", allowed: [{from, to}]}` |
| Either end does not exist | `404 {error: "node not found", missing: [...]}` |
| Both ends the same node | `400 {error: "self edge", nodeId}` |
| Property missing, wrong type, undeclared, or outside its enum | `400 {errors: [{field, message}]}` |

Every refusal carries what makes the next attempt possible. "Not allowed" without the pairs that are
allowed would leave a client guessing, and a form cannot offer a choice it has not been told about.

### One edge, two readings

A relationship is stored once. What differs between its two ends is only the name it is read under:
its own name looking outward, the declared inverse looking back, so a Team sees `OWNS` where the
Repository at the other end sees `OWNED_BY`. Storing it twice would let the two disagree, which is
the reason the ontology declares an inverse rather than expecting both to be written.

`GET /api/v1/edges?nodeId=` returns `displayName` already resolved for the end that asked, so a
caller renders what it is given rather than working out which way round it is.

### Constrained values

A property may declare an `enum`, and `DEPENDS_ON.kind` is the first to use it — `library`, `api`,
`event` or `data`, required. A free-text kind is barely worth storing: nobody can ask for "the
event-driven dependencies" if half of them say `events` and the rest say `async`. Declaring the set
is what makes the property answerable, and it is published through `GET /api/v1/ontology` so a form
offers the values rather than guessing them.

## Why identity cannot be edited

Because the key is derived, changing an identity property does not rename a node — it describes a
different thing. An update whose properties derive to a different key is therefore refused with the
properties responsible, and the editing form disables them rather than letting a user discover this
on save. Creating that other thing is a create.

The refusal names the properties by putting each changed one back on its own and asking whether the
key returns, rather than from a table of which properties feed which type's identity. A resolver that
starts consulting a different property stays correctly reported.

## Versioning

`version.yaml` declares the registry version, and an `Ontology` node records which version the
graph was built with; the application refuses to start against a graph written by a newer registry
than its own. Adding a type or an optional property is free. Renaming or removing anything needs a
version bump and a migration of the data already in the graph; no migration mechanism exists yet
([#33](../../issues/33)), so today that means not renaming or removing. `GET /api/v1/ontology`
returns the current registry as JSON, which is what drives the generic editing screen in the
frontend.

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
