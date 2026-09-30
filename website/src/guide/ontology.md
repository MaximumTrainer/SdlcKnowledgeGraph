<!-- GENERATED FROM docs/ONTOLOGY.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# Ontology

The ontology is the contract for what may exist in the graph. It names the entity types, the
relationship types, which types a relationship may connect, and what must be recorded about where
each fact came from.

The registry, provenance envelope and identity rules ([#18](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/18)) are served from
`GET /api/v1/ontology`; the registry-driven store that enforces them ([#19](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/19)) and
the code generation that removes the remaining duplication ([#20](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/20)) are both in
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
A node type may also say `meta: true` (the default is `false`), for a type that records the graph's
own bookkeeping rather than something in the software estate. There is no `default` or
`sensitivity` key; a property the loader does not recognise fails startup.

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

`SyncRun`, `ConnectorState`, `Ontology` and `ServicePrincipal` (a connector or agent registered as
a principal of its own, [AUTH](/guide/auth)) also exist as nodes, but they describe the graph itself
rather than the software, so they do not count against the nine. The registry marks them
`meta: true`. `GET /api/v1/ontology` serves the flag with every node type, and the web interface
leaves meta types out of its navigation; they can still be opened by their URL.

Person, Policy, Incident, ChangeRequest and Requirement arrive with the M2 and M3 connectors that
can actually populate them. They are registry additions, not code changes.

## Relationship types

Every relationship declares the types it may connect and the name of its inverse. The inverse is a
traversal concept, not a second stored edge.

| Type | From | To | Inverse | Impact (downstream) | Ownership |
| --- | --- | --- | --- | --- | --- |
| OWNED_BY | Repository, Service, CloudResource | Team | OWNS | none | owner |
| OWNS_RESOURCE | Repository, Service | CloudResource | OWNED_BY_REPO | propagates (forward) | inherits |
| DEPENDS_ON | Repository, Service | Repository, Service | DEPENDED_ON_BY | propagates (inverse) | none |
| HAS_PIPELINE | Repository | Pipeline | PIPELINE_OF | propagates (forward) | inherits |
| RELATES_TO_CI | Repository, Service | ConfigurationItem | CI_OF | none | none |
| BUILT_FROM | Artifact | Repository | BUILDS | propagates (inverse) | inherits |
| DEPLOYED_TO | Artifact | Deployment | DEPLOYMENT_OF | propagates (forward) | inherits |
| TO_ENVIRONMENT | Deployment | Environment | HOSTS | propagates (forward) | none |
| PROVIDES | Repository | Service | PROVIDED_BY | propagates (forward) | inherits |

This table is a copy of `edges.yaml`. The website's
[ontology reference](https://maximumtrainer.github.io/SdlcKnowledgeGraph/reference/ontology) is
rendered from the registry itself, so it is the one to trust if the two ever differ.

Three relationships carry properties of their own. `DEPENDS_ON` requires `kind` (`library`, `api`,
`event` or `data`) and accepts `manifest`, the file the dependency was read from, so an inferred
dependency can be traced back to its evidence. `OWNS_RESOURCE` accepts `rule`, which link rule
proposed it, and `BUILT_FROM` accepts `commitSha`.

A `CANDIDATE_LINK` relationship, for connections the planned link resolution engine is not confident
enough to assert, is described in [Adapters](/guide/adapters) and will be added to the registry with
that engine ([#28](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/28)).

## Provenance

Lasnoski's argument is that a fact without an origin cannot be trusted or corrected. Every node and
every edge carries the same envelope:

```kotlin
data class Provenance(
    val sourceSystem: String,   // "manual", "github", "servicenow", "aws": one sources.yaml declares
    val sourceId: String?,      // the identifier in that system
    val ingestedAt: Instant,
    val observedAt: Instant?,   // when the source says it was true
    val confidence: Double,     // 1.0 means reported by the system of record
    val inferred: Boolean,      // true when a rule produced it rather than a system reporting it
    val validFrom: Instant,
    val validTo: Instant? = null,   // null means current
    val syncRunId: String?,
    val writtenBy: String?,      // the principal's subject or service principal's name (#114, #115)
    val principalType: String?,  // "user", or "service" for a connector or agent (#115)
    val onBehalfOfTeam: String?, // the Team key a service principal acts for (#115)
)
```

The envelope is declared in the registry like the types are, in
[`provenance.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/ontology/v1/provenance.yaml),
so the `Provenance` GraphQL type, the `Provenance` TypeScript interface and the `provenance` section
of `ontology.json` are generated from it. `confidence` is the registry's one `float` property.

`writtenBy` and `principalType` say who made a write through the API: for a person, the subject of
the bearer token, which does not change when a username does, and `user`; for a registered
connector or agent, its registered name and `service`, with `onBehalfOfTeam` naming the team that
owns it ([AUTH](/guide/auth)). A scheduled connector run's facts carry none of them, and nor do facts
written before they existed.

Neo4j does not store nested maps, so these are flattened to `prov_` prefixed properties, and an
index on `prov_sourceSystem` is created for every type. Re-stating a node replaces its provenance
with that of the latest write; merging several sources' provenance on one node (accumulating
`sourceSystems`, keeping the highest `confidence`) is part of the connector work
([#22](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/22)).

A write through the API is `manual` unless it names another source, and then `confidence` is `1.0`,
`inferred` is `false` and `syncRunId` is null; a connector's run stamps its own source and run. The
user interface shows the envelope on every node's page; drawing inferred edges differently from
asserted ones will matter once a connector writes the first inferred one.

### Source systems

The values `sourceSystem` may take are declared in the registry too, in
[`sources.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/ontology/v1/sources.yaml)
([#117](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/117)), and published by `GET /api/v1/ontology` as `sources: [{name,
description}]`, in declaration order, and in `ontology.json`, so the drift check covers them:

| Source | What states it |
| --- | --- |
| `manual` | a principal writing through the API in its own name |
| `github`, `servicenow` | those connectors |
| `github-actions`, `dogfood-seed` | deployment reports and the seed job, through the ingest endpoints |
| `aws` | the AWS connector, still to come |
| `sdlc-knowledge-graph` | the application's own `SyncRun` and `ConnectorState` records |

A node or edge write may name one of them as `{"provenance": {"sourceSystem": "github"}}` beside its
`props`; a source not declared is refused with `400 {error: "unknown source system", sourceSystem,
known}`. Naming any source but `manual` also needs the `graph:write:<source>` scope
([AUTH](/guide/auth#source-scopes)), so a registry entry is also the name of a permission: it is
lower-case words joined by hyphens, and the registry refuses to load a name that is not, a name
declared twice, or a list without `manual`.

## Traversal semantics

Two questions come before any other: "what depends on this change?" and "why did the deployment
fail?" ([#21](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/21)). Both are walks over
several hops, and the registry, not the query, says which edges a walk may take.

### Which edges a change travels along

An edge flagged `impact: propagates` in
[`edges.yaml`](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/backend/src/main/resources/ontology/v1/edges.yaml)
is one a change travels along; an edge without the flag is never walked by an impact query.
`downstream` says which way the change travels: `forward`, the default, as the edge is stored, or
`inverse`, against it. `DEPENDS_ON` points from the dependent to what it depends on, and a change
travels the other way, so its downstream reading is `DEPENDED_ON_BY`; `BUILT_FROM` likewise reads
`BUILDS`. A downstream walk from a repository therefore reaches its dependents, the services it
provides, its pipelines, the cloud resources it owns, the artifacts built from it, their
deployments and the environments those went to. An upstream walk reads every propagating edge the
other way: what a node's changes come from.

`TraversalFilterBuilder` turns the flags into the list of relationship types and directions a walk
takes. No query names an edge, so flagging a new edge in the registry adds it to the blast radius
without a code change. The registry refuses a `downstream` on an edge that does not propagate.

Each step of an answer is named as it was walked: an edge's own name along its stored direction,
its declared inverse against it. The path from `shared-lib` to a database its dependent owns reads
`DEPENDED_ON_BY, OWNS_RESOURCE`.

### Confidence

A path is as trustworthy as all of its edges together, so its confidence is the product of each
edge's provenance `confidence`, stored as `prov_confidence`. An edge with no `prov_confidence`,
written before provenance was recorded, counts as `1.0`. Several paths may reach one node: the most
confident explains it, the shorter breaking a tie, and the node is `inferred` only when that path
holds an edge whose provenance says `inferred` (`prov_inferred`). A node reached for certain is not
made doubtful by a guess that also reaches it.

`minConfidence` (default `0.5`) leaves out nodes whose best path is below it; they are still
counted, as `byType.excluded`. `depth` is 1 to 5 (default 3). At most 1,000 affected nodes are
listed, nearest first, then most confident; `byType` counts all of them and `truncated` says the
list was cut. Closed facts, those with a `validTo`, are history and are not walked.

### Ownership

`ownership: owner` marks the edge that names an owner, `OWNED_BY`. `ownership: inherits` passes
ownership on against the direction a change travels: a cloud resource with no owner of its own is
owned by whoever owns the repository that owns it (`OWNED_BY_REPO, OWNED_BY`), and a deployment by
whoever owns the repository its artifact was built from (`DEPLOYMENT_OF, BUILT_FROM, OWNED_BY`).
Only a propagating edge can inherit, since only it has a direction; the registry refuses the flag
elsewhere. The nearest owners answer: a node's own `OWNED_BY` when it has one, otherwise the
owners of the nearest things it inherits from, each team once, by its most confident path.
`DEPENDS_ON` does not inherit: a library's owner does not own the repositories that use it.

### Why a deployment failed

A deployment's lineage is the one the deployment ingest writes: `Artifact -BUILT_FROM{commitSha}->
Repository` and `Artifact -DEPLOYED_TO-> Deployment -TO_ENVIRONMENT-> Environment`. The window runs
from the last successful deployment of the same repository to the same environment up to the
failed one. A repository the failed one reaches through `DEPENDS_ON` in one or two hops, and that
was deployed to the same environment inside the window, is a changed dependency. With no earlier
success the window has no start, which is itself reported. A deployment is failed when its
`status` is `FAILED`; any other status has nothing to explain and answers with no reasons.

### The queries

| Request | Answer |
| --- | --- |
| `GET /api/v1/graph/impact?nodeId=Type:key&depth=3&minConfidence=0.5&direction=downstream` | `{root, depth, direction, minConfidence, truncated, affected: [{node, distance, confidence, inferred, path}], byType}` |
| `GET /api/v1/graph/why-failed?deploymentId=Deployment:key` | `{deployment, status, artifact, commitSha, repository, pipeline, environment, changedDependencies, precedingSuccessfulDeployment, reasons}` |
| `GET /api/v1/graph/owners?nodeId=Type:key` | `{node, owners: [{team, via, confidence}]}`; no owners is `[]`, not 404 |

A node id is `Type:key`, the `id` every node has. A malformed parameter is `400 {error, field}`,
and a node that resolves to nothing `404`. The deployment is a query parameter rather than a path
segment because its key holds `/` and `#`. GraphQL has `impact` and `whyDeploymentFailed`, whose
nodes are the generated `<Type>Node` types. All of them need `graph:read`.

### Impact of a change, ranked for an agent

`POST /api/v1/impact` ([#87](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/87)) asks
the question a coding agent asks before it edits: "I am about to change this repository - what runs
on it, in which environments, who owns each of those things, and which matters most?" The answer is
built to be put into a context pack under a token budget, so it is ranked, bounded and stable: two
identical requests against an unchanged graph return byte-identical bodies.

```json
{ "repositoryKey": "github.com/acme/payments", "paths": ["infra/db.tf"], "sha": "4f1c2d9", "depth": 2, "limit": 50 }
```

Only `repositoryKey` is required. `depth` is 1 to 4 (default 2) and `limit` 1 to 500 (default 50);
outside them the request is `400 {error, field}`, the error naming the bound, and a repository the
graph does not hold is `404`. It is a POST because its input is a body, not because it writes: it
needs `graph:read`, and a read-only instance answers it ([Authentication](/guide/auth#scopes)).

**The walk** is the downstream blast radius above, from the repository, with no confidence floor:
every hit carries its `confidence` and `inferred` instead. The issue named the edges `DEPENDS_ON`,
`BUILT_BY`/`PRODUCES`, `DEPLOYED_TO`, `RUNS_ON` and `OWNED_BY`; the registry's names are what is
walked, because the walk is whatever `impact: propagates` says:

| The issue's edge | What the registry walks |
| --- | --- |
| `DEPENDS_ON` | `DEPENDS_ON`, read `DEPENDED_ON_BY` |
| `BUILT_BY` / `PRODUCES` | `BUILT_FROM`, read `BUILDS` (Repository to Artifact) |
| `DEPLOYED_TO` | `DEPLOYED_TO` (Artifact to Deployment), then `TO_ENVIRONMENT` |
| `RUNS_ON` | no such edge; a running service is `PROVIDES`, infrastructure `OWNS_RESOURCE` |
| `OWNED_BY` | not walked: it names owners, which every hit carries |

Each node is reached by its **nearest** path - the fewest hops, then the most confident - because
how far a change travels is the length of the shortest way there. That path is the hit's `hops` and
its citation.

**Environments.** `Environment.tier` is `production`, `pre_production`, `development` or `other`.
It is optional: an environment written before it existed, or by a writer that does not set it, is
read as `other`, never guessed from its name, since there is no migration mechanism yet
([#33](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/33)). Set it by editing the
Environment, in the web interface or through `PUT /api/v1/nodes/Environment/{key}`. A hit is placed in an environment by the propagating edges
that end at one (`TO_ENVIRONMENT`), found from the registry like every other edge: a Deployment
runs in the environment it targeted, an Environment is its own, and a node in several environments
counts as in the most critical of them. A node in no environment is weighted as `other`.

**Scoring version 1**, which every answer states as `scoring.version` so a consumer can notice a
change of formula ([ADR-0011](/adr/0011-impact-scoring-versioned-and-deterministic)):

```text
score = min(1, 1 / (1 + hops) * tierWeight * pathBoost)
```

| Tier | Weight |
| --- | --- |
| `production` | 1.0 |
| `pre_production` | 0.6 |
| `other`, and no environment | 0.5 |
| `development` | 0.3 |

`pathBoost` is 2 for a hit a requested path names (below) and 1 otherwise. The weights put a
production deployment two hops away (0.33) above anything outside an environment one hop away
(0.25), and that above a pre-production deployment two hops away (0.2). The hits are ordered by
score descending, then hops ascending, then node id ascending, a total order, so nothing is left
to the order the database returned rows in. At most `limit` are returned; `truncated: true` says
there were more, and how many more is not said.

**Owners** are #21's: the nearest `OWNED_BY` of each hit, directly or through what it inherits
ownership from. CODEOWNERS needs nothing extra: the GitHub connector already records each team a
CODEOWNERS file names as an `OWNED_BY` edge carrying the `pathPatterns` it was named against
([Adapters](/guide/adapters#the-github-connector)); a CODEOWNERS handle that is a person rather than a
team stays in the repository's `codeowners` property and is not an owner here.

**Paths.** The GitHub connector indexes what a repository holds: an `IacFile` per
infrastructure-as-code file (`CONTAINS_IAC`), with the resource identifiers it names, and the
`manifest` each `DEPENDS_ON` edge was read from. `pathFilter` says what the requested `paths` could
do:

| `pathFilter` | When | Effect |
| --- | --- | --- |
| `not_requested` | No `paths` | None |
| `not_applied` | The repository has no index entry at all | None: the unfiltered answer, said to be unfiltered |
| `applied` | The repository has an index | `matchedPaths` lists the requested paths the index holds; a hit an `IacFile` among them names, by resource id, name or key, gets `pathBoost` 2 and `pathMatched: true` |

Nothing is removed from the answer by a path: which parts of a repository a source file affects is
the caller's code index to say, not this graph's. A matched manifest is reported but boosts nothing,
because what a manifest names is what the repository depends on - upstream of a change.

**A commit.** A `sha` would restrict deployments to those whose artifact contains the change. That
needs Change nodes ([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)), which
do not exist yet, so a request with a `sha` answers `"changeScope": "unknown"` and the unscoped hits;
one without says `not_requested`.

Each hit is `{node, hops, score, confidence, inferred, tier, environment, pathMatched, owners,
citation}`, where `citation` is `{nodeKey, edgePath, provenance}`: the node's id, the edges of the
path that reached it with their confidences, and the node's own provenance, so a consumer cites the
facts rather than the query. GraphQL answers the same as `changeImpact(input: ChangeImpactInput!)`:
`impact` is #21's blast radius, and GraphQL cannot overload a field by its arguments.
`ChangeImpactService` does the work in front of #21's `ImpactQueryPort`, and `ImpactScorer` holds the
formula and the order.

The issue also asked that a one-hop `GET /api/v1/impact/{repositoryId}` stay as a deprecated alias
answered by this endpoint at depth 1. No such route exists: the one-hop repository impact is
`GET /api/v1/graph/repositories/{repoId}/impact`, already deprecated in favour of `GET
/api/v1/graph/impact`, and both are left as they are.

### How it runs

Each walk is one Cypher statement, a Neo4j 5 quantified path pattern whose relationship types come
from the registry and whose direction is checked per step, in `Neo4jImpactQueries` behind
`ImpactQueryPort`. The issue asked for APOC's `apoc.path.expandConfig`; it is not used, because the
instances this runs against, the dogfood one among them, have no plugins, and nothing else in the
application needs APOC. Plain Cypher keeps the query portable at the cost of enumerating paths
rather than expanding a frontier, which the depth bound keeps affordable: on a graph of 10,003
nodes a depth-5 walk reaching 769 nodes measured a p95 of 266 ms locally (`ImpactPerformanceIT`,
which asserts a looser ceiling so a slow CI runner does not fail it).

`GET /api/v1/graph/repositories/{repoId}/impact` is deprecated: it answers from the same walk, cut
back to its old `{repoId, dependents, cloudResources, deployments}`, with `Deprecation: true` and a
`Link` to its successor.

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
| `POST /api/v1/nodes/{type}` with `{props, provenance?}` | `201` with the derived identity, and a `Location` |
| `GET /api/v1/nodes/{type}?limit=&cursor=` | `200 {items, nextCursor}`, in key order |
| `GET /api/v1/nodes/{type}/{key}` | `200` or `404` |
| `PUT /api/v1/nodes/{type}/{key}` with `{props, provenance?}` | `200`, same id and same key |
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
| `provenance.sourceSystem` not declared in `sources.yaml` | `400 {error: "unknown source system", sourceSystem, known}` |
| A source the token has no `graph:write:<source>` for | `403 {error: "insufficient scope", required, held}` |

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
([#33](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/33)), so today that means not renaming or removing. `GET /api/v1/ontology`
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
