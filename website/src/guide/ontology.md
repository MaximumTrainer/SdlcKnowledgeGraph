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

The registry inverts that. A YAML file is the source of truth. This is the shape of an entry in
`nodes.yaml` — properties are a list, each with a `name`, a `type` (`string`, `int`, `float`,
`boolean`, `instant` or `string[]`), `required`, a `description` and `examples`, and where they apply
an `enum`, a `format` and a `deprecated` marker. A type also carries whole example nodes and the
questions it helps answer; [Describing a property](#describing-a-property) says what each key is for:

```yaml
# backend/src/main/resources/ontology/v1/nodes.yaml
nodes:
  Repository:
    description: A git repository, the anchor for most of the graph.
    identity: [host, org, name]
    questions:
      - Which team owns this repository?
    examples:
      - { url: "https://github.com/acme/payments", defaultBranch: main, topics: [billing], codeowners: ["@acme/payments"] }
    properties:
      - { name: url, type: string, required: true, format: url, description: "The git remote, in any form", examples: ["https://github.com/acme/payments"] }
      - { name: host, type: string, required: false, description: "Host of the remote. Derived from url", examples: [github.com] }
      - { name: defaultBranch, type: string, required: true, description: "The branch changes are merged into", examples: [main] }
      - { name: topics, type: "string[]", required: true, description: "Topics the forge lists for the repository", examples: [[billing]] }
      - { name: visibility, type: string, required: false, description: "Who may read it", enum: [public, private, internal], examples: [private] }
      - { name: serviceId, type: string, required: false, description: "The service it provides, from before PROVIDES", examples: [payments-api], deprecated: { since: 1.3.0, replacedBy: PROVIDES } }
```

The registry's version lives in `version.yaml` (semver, currently `1.3.0`), not in `nodes.yaml`.
A node type may also say `meta: true` (the default is `false`), for a type that records the graph's
own bookkeeping rather than something in the software estate. There is no `default` or
`sensitivity` key: a key the reader does not recognise, at any level, fails startup and fails the
build (#81), rather than being read past as it once was.

A node type may name a `displayProperty`: the property that labels one of its nodes where a person
reads it, such as the graph view ([#9](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/9)).
It must be one of the type's own properties, or startup fails with `node type 'Team' displays
'title', which it does not declare`. A node whose display property is absent or blank is labelled
with its key, and so is every node of a type that names none, so no label is ever empty. Every type
in the registry names one: `name` for most, `number` for a change request or an incident, `path` for
an IaC file, `deployedAt` for a deployment. `GET /api/v1/ontology` serves it with each node type.

A node type may also name an `alias`: properties that together identify one of its nodes beside its
key, unique where all of them are present and consulted before the key
([#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88)). An alias must name properties
the type declares, none of them part of its identity and none of them required, or startup fails.
Only Repository names one, `[provider, providerId]`; see
[A repository's provider id survives a rename](#a-repositorys-provider-id-survives-a-rename).

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

### A repository's provider id survives a rename

The remote is the identity to show, and the wrong one to hold on to: GitHub keeps a repository's
numeric id across a rename and a transfer between organisations, and a system that authenticates as
a GitHub App addresses repositories by that id
([#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88),
[ADR-0013](/adr/0013-provider-id-is-an-alias-not-the-key)). So a Repository has `provider`
(`github`, `gitlab` or `other`) and `providerId` beside its key, and the registry declares them its
**alias**:

```yaml
  Repository:
    identity: [host, org, name]
    alias: [provider, providerId]
```

An alias finds a node; it does not name it.

- **Unique where present.** At startup a constraint is created over the alias's properties together,
  for every type that declares one: `REQUIRE (n.provider, n.providerId) IS UNIQUE`. A repository with
  no provider id is outside it, and the same number at GitHub and at GitLab is not a collision.
- **The provider can be left out on github.com and gitlab.com**, where it is the host's. Anywhere
  else it has to be named, and half an alias is `400` naming the missing half (`provider is required
  with providerId`). A provider id sent as a JSON number is stored as the string it is.
- **Consulted before the key.**
  - A `POST` whose provider id another node holds is `409 {error: "node exists", existingId, alias}`.
  - A `PUT` that carries the provider id the node already holds, with a url that derives a different
    key, **renames the node in place** and answers `200` with the node under its new key. It may be
    addressed at the old key, or at the new one while nothing holds it. The node keeps every edge, and
    the key it left is added to `previousKeys` on its provenance.
  - A connector's delta does the same.
- **Only a node that already holds the id is renamed by it.** A `PUT` that sets a provider id and
  changes the url at once is refused as a change of identity, as before, and a provider id once held
  is not replaced by another (`409 identity properties are immutable`, naming `providerId`).
- **A rename onto a key another node holds is refused**, naming that node. Two nodes for one
  repository are a merge, which is the review queue's to propose
  ([#74](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/74)). A connector's delta writes
  the reported node without the alias instead, so the sync does not fail and the node that holds the
  id keeps it.
- **The old remote still finds it.** When nothing holds a key now, the node that held it before a
  rename answers.

| Request | Answer |
| --- | --- |
| `GET /api/v1/repositories/by-provider/{provider}/{providerId}` | The repository, or `404`; a provider the ontology does not declare is `400 {error, field: "provider"}` |
| `GET /api/v1/repositories?url=` | The repository the remote resolves to now or resolved to before a rename, as a list of at most one; `[]` when none does |
| `GET /api/v1/repositories/by-key?key=` | As before, falling back to a key the repository had before a rename |

The issue asked for `GET /api/v1/repositories?url=` to return the node. It returns a list of at most
one, because without `url` the same route lists every repository and a route should answer one shape.
Each repository in these answers carries `provider`, `providerId`, `previousKeys` and `orgRepo`. All
three routes need `graph:read`.

Whatever hangs off a repository by its key rather than by an edge keeps the old key after a rename:
a Pipeline's `repoKey` and a Change's `repositoryKey`, and the keys derived from them. Changes are
still found, because impact's sha scoping reads `previousKeys` too (below).

### `orgRepo` is derived, not accepted

Before [#8](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/8) a Repository was named by
a free-text `orgRepo`. The registry no longer declares it, and #88 finishes the move:

- **On input it is refused as derived.** A node write that sends it is `400` with `orgRepo is derived
  from url and is not accepted`, rather than as an unknown property, so the caller is told what to
  send instead. The seed ingest refuses it the same way.
- **The GraphQL `RepositoryInput`** takes `url` in its place. It used to declare `orgRepo: String!`
  while its resolver read `url`, so that mutation could not have succeeded.
- **On output it is still emitted**, as `org/name`, by the repository endpoints and by the GraphQL
  `Repository` type, until the typed Kotlin class is migrated off it. The node API does not emit it:
  what a node read answers is what an edit sends back, and an edit sending it would be refused.

`defaultBranch`, `topics` and `codeowners` are required because the typed Kotlin class carries them.

Seven of the core types keep typed Kotlin classes (Repository, Team, Pipeline, Artifact, Deployment,
Environment and CloudResource), because the hand-written traversal code benefits from compile-time
safety. A startup check compares each class against the registry and fails fast if they drift.
Service, ConfigurationItem and everything a connector introduces later exist only in the registry
and are handled as generic nodes, so a new type does not require a new Kotlin class.

The drift check is directional. Every **required** registry property must exist on the class, and
every class property must be declared in the registry; an optional registry property may be absent
from the class. That is what lets the registry describe the target identity model while the
classes still carry legacy properties. A class property is one its primary constructor takes, what
the node stores: a property the class computes from those, like `Repository.orgRepo`, is not
expected in the registry (#88).

## Describing a property

The registry is the contract an agent reads to know what it may ask
([#81](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/81)). A property named `status`
with no description, no example and no allowed values leaves a reader guessing, and a guess is a
query for a value that does not exist. So every property says what it is and shows a value, and a
closed set says so:

| Key | Says | Enforced |
| --- | --- | --- |
| `description` | What the property holds, in a phrase of at least 20 characters that is more than its name | by the lint |
| `examples` | Values the API accepts; the first is shown wherever one is (a JSDoc `@example`, the editor's placeholder, the Markdown) | by the lint, and every example is held to the API's own validator by `ShippedOntologyDescriptionTest` |
| `enum` | The only values allowed, in one naming convention per enum (snake_case, kebab-case or UPPER_SNAKE_CASE) | on write: `status must be one of PENDING, IN_PROGRESS, ...` |
| `format` | A string's shape: `url`, `email`, `sha256`, `arn`, `semver`, `iana-tz`, `rrule` or `instant` | on write, only when there is a value: `expected url` |
| `formatWhen` | The sibling values under which the format applies, such as `{ provider: aws }` for an ARN | with the format |
| `deprecated` | `{ since, replacedBy }`: kept for old writers and data, and what to use instead, a property of the same type or an edge type | in generated code, as `@deprecated` |

A node type adds `examples`, whole nodes the API accepts, and `questions`, what a reader answers with
it. An edge type may carry both too, and its properties describe themselves like a node's.

### What reads it

- `GET /api/v1/ontology` serves all of it, and `ontology.json` is its committed copy.
- The generated GraphQL puts each description on its field as a docstring, generates an enum type per
  enum property (`DeploymentStatus`, with `container-image` spelt `container_image` and a docstring
  saying how it is stored), names it in the field's docstring, and marks a deprecated field
  `@deprecated(reason: "since 1.3.0, replaced by environmentKey")`. The fields themselves stay
  `String`, so a value stored before its enum existed still reads.
- The generated TypeScript declares a union per enum property (`DeploymentStatus`), types the property
  with it, and documents every property with its description, first example and deprecation.
- The node editor offers an enum as a select, shows the description as the field's help, tied to it
  with `aria-describedby`, and the first example as a text field's placeholder. A stored value outside
  the enum stays visible as an extra option rather than silently changing.
- The [ontology reference](https://maximumtrainer.github.io/SdlcKnowledgeGraph/reference/ontology)
  shows each property's values, format, deprecation and example, each type's questions and one
  example node.
- `GET /api/v1/ontology?format=markdown` renders it as prompt text, below.

### The Markdown rendering

`GET /api/v1/ontology?format=markdown` returns `text/markdown`, cacheable like the JSON, and the same
bytes on every call, so a prompt built from it stays cached. `format=json` is the default and may be
named; any other format is `400 {error: "unknown format", format, supported: ["json", "markdown"]}`.
An agent tool that hands this on, `describe_ontology`, belongs to the MCP server
([#31](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/31)), which is not built yet.

It must stay under 12,000 characters, so it leaves room for the question, and a test holds the
shipped registry to that. To fit, it carries what a query needs and leaves the rest to the JSON:

- meta types, and relationships with no end left once they are gone (`PRODUCED`), are left out;
- deprecated properties are left out;
- property descriptions are left out: each property is one line of its type (`!` when required), its
  allowed values `a|b`, its `[format]` and `e.g.` its first example;
- each relationship is listed once, under Relationships, with its ends and inverse, not again beside
  each type it connects.

When the registry grows past the bound, the rule is to trim what the rendering carries, in that
spirit, and record it here, not to raise the bound.

### The lint

`./gradlew ontologyLint` fails the build on any finding, naming its registry path and rule code, and
groups findings under the type they are about. It runs in `./gradlew check`, in the pre-commit hook
when the registry is staged, and so in CI. `--warn-only` reports ONT009 without failing; every other
rule is a fact about the registry, not a matter of taste.

| Code | Finding |
| --- | --- |
| ONT001 | A property or node type without a description |
| ONT002 | A property without examples |
| ONT003 | An example the API would refuse: the wrong type, outside the enum, not of the format; or an example node that lacks a required property or sets an undeclared one |
| ONT004 | A node type without an example node |
| ONT005 | A core type (the nine below) that names no questions it helps answer |
| ONT006 | An enum with a duplicated value, a value in no naming convention, or values in more than one |
| ONT007 | An unknown format, or a `formatWhen` naming a property the type does not declare |
| ONT008 | A deprecation replaced by something that is neither a property of the same type nor an edge type |
| ONT009 | A description under 20 characters, or one that only repeats the property's name (a warning with `--warn-only`) |
| ONT010 | An edge type without a description, or naming an undeclared node type at either end |
| ONT011 | A deprecated identity property |

```text
Ontology lint: 1 errors, 0 warnings
nodes.Repository
  nodes.Repository.properties.topics: missing description [ONT001]
```

The lint's rules are tested against small fixture registries in buildSrc, which CI runs as its own
step (`./gradlew -p buildSrc test`), because the main build cannot reach buildSrc's tests.

### Enums, and the writers they had to agree with

An enum is enforced on write through the node, edge and seed APIs, so declaring one must not make a
real writer start failing. Each set below is what the writers already send, and where the issue's
list and the code disagreed the code won or both changed together:

| Property | Values | Where it differs from the issue, and why |
| --- | --- | --- |
| `Deployment.status` | `PENDING`, `IN_PROGRESS`, `SUCCESS`, `FAILED`, `ROLLED_BACK`, `CANCELLED` | Upper case: the deployment ingest, why-failed and every fixture already speak `SUCCESS` and `FAILED`, so the lint allows UPPER_SNAKE_CASE |
| `Pipeline.lastRunStatus` | `success`, `failure`, `cancelled`, `in_progress`, `unknown` | Writers changed with it: the deployment ingest maps its report's `SUCCESS` to `success` and `FAILED` to `failure`, and the dogfood seed folds GitHub's conclusions onto these (`timed_out` is `failure`, `queued` is `in_progress`, `skipped` is `unknown`) |
| `Pipeline.provider` | `github-actions`, `gitlab-ci`, `azure-pipelines`, `jenkins`, `other` | The deployment ingest writes a report's provider as given, without the validator: it is part of the key, so folding an unknown one into `other` would merge pipelines. The conformance report lists any such value |
| `Environment.type` | `development`, `test`, `staging`, `production`, `ephemeral`, `other` | `other` added: the deployment ingest used to copy the environment's name into its type, so `dogfood` was a type. It now types an environment by its name where the name is one of these, and as `other` where it is not |
| `Artifact.artifactType` | `container-image`, `jar`, `npm-package`, `python-wheel`, `helm-chart`, `terraform-module`, `binary`, `other` | kebab-case, because the ingest writes `container-image` |
| `SyncRun.status` | `RUNNING`, `SUCCESS`, `PARTIAL`, `FAILED` | Upper case, as the sync recorder writes them |
| `SyncRun.mode` | `FULL`, `INCREMENTAL`, `WEBHOOK` | As above |
| `ConnectorState.lastStatus`, `lastRunStatus` | `SUCCESS`, `PARTIAL`, `FAILED` | As above |
| `CloudResource.provider` | `aws`, `azure`, `gcp` | |
| `sourceSystem` of ConfigurationItem, ChangeRequest, Incident | `servicenow`, `jsm` | |
| `Service.tier` | `platinum`, `gold`, `silver`, `bronze` | No writer sets it yet |
| `OWNS_RESOURCE.rule` | `manual`, `tag`, `deployment`, `iac`, `naming` | |

The enums the registry already had (`visibility`, `Repository.provider`, `Environment.tier`,
`changeType`, `ecosystem`, `IacFile.format`, `PullRequest.state`, `ExternalWorkItem.system`,
`DEPENDS_ON.kind` and `scope`, `principalType`) are unchanged.

A value is checked against its enum only on write. Data written before the enum was declared, or by
a writer that does not go through the validator (the deployment ingest, the connectors' graph
deltas, the sync recorder), is left as it is and still reads: the GraphQL fields stay strings for
that reason. `./gradlew ontologyLint --report-data` lists it, against the graph named by `NEO4J_URI`,
`NEO4J_USERNAME` and `NEO4J_PASSWORD`: each property, each value outside its enum and how many facts
hold it. The report is informational and never fails the build; it is what a migration
([#33](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/33)) would be written from.

### Formats, and what they accept

A format is declared only where every current writer conforms, and checked only when there is a value:

| Property | Format | Accepts |
| --- | --- | --- |
| `Repository.url`, `PullRequest.url`, `Change.url` | `url` | any `scheme://…`, which covers https, ssh, git and `chorus://`, and the scp-like `git@host:org/name` form |
| `Team.email` | `email` | `name@host.tld` |
| `Artifact.digest` | `sha256` | `sha256:` and 64 lower-case hex digits |
| `CloudResource.resourceId` | `arn`, when `provider` is `aws` | `arn:partition:service:region:account:resource`; an Azure id or GCP asset name is not held to it |
| `Ontology.version` | `semver` | `1.3.0`, with an optional pre-release and build |

A Repository's `url` is judged as it will be stored, canonicalised to `https://host/org/name`, so any
remote GitRemoteParser reads (`github.com/acme/payments`, `acme/payments`) passes, through the seed
as well as the node API. `ExternalWorkItem.uri` declares no format: it is opaque to everything but the
system that owns it. An `instant` property needs none, since its type already says it.

### Deprecations

Deprecated in 1.3.0, and kept until a major version removes them with a migration:

| Property | Use instead |
| --- | --- |
| `Repository.serviceId` | the `PROVIDES` edge |
| `Pipeline.repoId` | `repoKey` |
| `Artifact.repoId` | the `BUILT_FROM` edge |
| `CloudResource.repoId` | the `OWNS_RESOURCE` edge |
| `Deployment.artifactId` | `artifactKey` |
| `Deployment.environmentId` | `environmentKey` |
| `ConfigurationItem.serviceId` | the `RELATES_TO_CI` edge |

`Pipeline.repoId` and the two Deployment ids stay required of a writer, because the typed classes
still read them. `Repository.orgRepo` is not deprecated: it is not a registry property at all.

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

Three types trace a deployment back to the work it delivered
([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)):

| Type | What it represents |
| --- | --- |
| Change | A commit in a repository: `repositoryKey`, `sha` and `committedAt`, with an optional `baseSha`, `title`, `author` and `url` |
| PullRequest | A pull or merge request: `repositoryKey`, `number` and `url`, with `state` (`open`, `merged` or `closed`), `mergedAt` and `title` |
| ExternalWorkItem | A task, ticket or issue in the system that owns it: `uri` and `system` (`chorus`, `jira`, `linear`, `github` or `other`), with `externalKey` (for example `CH-42`) and `title` |

A work item is a reference to the system that owns it, not a copy: status, assignee and history
stay there ([ADR-0012](/adr/0012-external-work-items-are-references-not-copies)). Its human
identifier is `externalKey`, not `key`, because every node's `key` is its identity. No connector
writes these types yet: the GitHub connector's commits and pull requests
([#23](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/23)) and a change feed are later
work. They are written through the node API, and they land in the graph directly. The proposal
queue that would hold them for review ([#74](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/74))
is not built.

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
| INTRODUCED_IN | Change | Repository | HAS_CHANGE | none | none |
| MERGES | PullRequest | Change | MERGED_BY | none | none |
| CONTAINS | Artifact | Change | CONTAINED_IN | propagates (inverse) | none |
| IMPLEMENTS | Change | ExternalWorkItem | IMPLEMENTED_BY | none | none |
| TRACKED_IN | ExternalWorkItem | Team | TRACKS | none | none |

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
    val previousKeys: List<String>, // keys the node had before a rename through its alias (#88)
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
with that of the latest write, except `validFrom`, which says when the fact began and so is kept
while it holds ([#93](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/93); see
[Freshness, and reading as of an instant](#freshness-and-reading-as-of-an-instant)); merging several sources' provenance on one node (accumulating
`sourceSystems`, keeping the highest `confidence`) is part of the connector work
([#22](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/22)).

`previousKeys` lists the keys a node was known by before it was renamed through its alias, oldest
first, each once ([#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88)). It is
history rather than a statement of the latest write, so only a rename writes it, as
`prov_previousKeys`, and every other write leaves it alone. It is empty for a node never renamed and
always empty on an edge.

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

### Freshness, and reading as of an instant

A graph that lags reality is worse than no graph, because an agent trusts what it reads
([#93](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/93)). So every source system has a
**freshness window**: how long its facts stay fresh once it has stated them. It is a day unless
configuration names another, under `freshness.windows` by the source's name in `sources.yaml`:

```yaml
freshness:
  default-window: PT24H   # FRESHNESS_DEFAULT_WINDOW
  windows:
    aws: PT6H             # FRESHNESS_WINDOWS_AWS
```

A window must be positive, and a source name `sources.yaml` does not declare stops the application
from starting rather than leaving that source on the default unnoticed. A source whose name holds a
hyphen, such as `github-actions`, cannot be spelt as an environment variable; set it in
`SPRING_APPLICATION_JSON` or a configuration file instead. The windows are configuration, not
registry, so they are not in `ontology.json` or the ontology's Markdown rendering:
`GET /api/v1/ontology` adds them to its answer as `freshness: {defaultWindow, windows}`, where `windows`
maps every declared source, in declaration order, to its window as an ISO-8601 duration.

**Stale.** Every node and edge a REST read returns carries `provenance.stale`, as does the
`Provenance` GraphQL type. It is computed when the fact is read, never stored, since a stored flag
would itself go stale: a fact is stale when it is current (no `validTo`, or one still to come) and
its source last stated it (`ingestedAt`) longer ago than the source's window. A fact that has ended
is history rather than a claim about now, so it is never stale. The web interface marks a stale node
on its page. `stale` is not a registry property, so the generated `Provenance` types do not declare
it; the GraphQL schema adds it by extension. The provenance cited by the impact answers does not
carry it.

**Lag.** How far behind each source is, against its window, is the `freshness` component of
`/actuator/health` ([Observability](/guide/observability#health)) and `GET /api/v1/freshness`, which
the web interface's list page reads to name any source that is behind.

**As of an instant.** A fact holds over the half-open interval `[validFrom, validTo)`: from the
instant it began up to, not including, the instant it ended, so of one deployment replacing another
at the same instant exactly one holds at any time. These reads take `asOf`, an ISO-8601 instant such
as `2026-09-30T01:30:00Z`, and answer as the graph held then:

| Read | With `asOf` |
| --- | --- |
| `GET /api/v1/nodes/{type}/{key}`, and `by-key` | The node, or `404` unless its interval contains the instant |
| `GET /api/v1/edges?nodeId=` | Only the edges whose interval contains it, to a node whose interval contains it too |
| GraphQL `node(id, asOf)` | The node, or `null` |
| GraphQL `edges(nodeId, direction, edgeType, asOf)` | As `GET /api/v1/edges` |

Anything but an instant, a bare date included, is `400 {error, field: "asOf"}`, the error saying
what was wrong (in GraphQL a `BAD_REQUEST` naming it). Without `asOf` every read is the current view,
closed facts included, exactly as before. A later history of property versions
([#33](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/33)) selects versions by the same
interval and the same parameter.

**Closing a fact.** A `PUT` on a node that carries `provenance.validTo`, an instant, beside its
`props` says it ended then, or keeps it ended. A `validTo` before the node's `validFrom` is `400`
naming `provenance.validTo`. A `POST` of a node or an edge may not carry one: a new fact that has
already ended is a contradiction. Edges have no `PUT`, so closing an edge is left to the connectors.
Closing a node does not remove it or its edges, so a closed node with edges is still refused a plain
`DELETE`. A write that leaves `validTo` out states the fact holds now: restated while current, it
keeps the `validFrom` it began with; restated after it was closed, it begins again, with `validFrom`
the time of that write.

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
| `GET /api/v1/graph/neighbourhood?nodeId=Type:key&depth=1&nodeTypes=A,B&edgeTypes=X,Y&direction=both` | `{root, nodes: [{id, type, key, label, distance, props, provenance}], edges: [{id, type, inverse, from, to, confidence, inferred}], truncated}` |

A node id is `Type:key`, the `id` every node has. A malformed parameter is `400 {error, field}`,
and a node that resolves to nothing `404`. The deployment is a query parameter rather than a path
segment because its key holds `/` and `#`. GraphQL has `impact` and `whyDeploymentFailed`, whose
nodes are the generated `<Type>Node` types. All of them need `graph:read`.

### The neighbourhood of a node

`GET /api/v1/graph/neighbourhood` ([#9](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/9))
is what the graph view draws. Unlike the queries above it walks every edge type, not only the ones
flagged `impact: propagates`, because it answers "what is around this" rather than "what does a
change reach":

- `depth` is 1 to 3 hops (default 1); anything else is `400` naming `depth`. `direction` is `out`
  (along stored edges), `in` (against them) or `both` (the default).
- `nodeTypes` and `edgeTypes` are comma separated and constrain the walk itself: a node of a type
  left out is neither answered nor walked through. With no `nodeTypes`, every type except the
  `meta: true` ones is walked, since a sync run links to everything it wrote and would otherwise
  flood every neighbourhood. A type the registry does not declare is `400` naming the parameter.
- At most 500 nodes are answered, the root among them, nearest first; `truncated: true` says there
  were more. Each node carries the hop `distance` at which it was first reached and a `label` from
  its type's `displayProperty`. A fact whose `prov_validTo` is set is closed and is not walked.
- An edge's `id` is `type:from>to`, the same in every answer, so a client merging one neighbourhood
  into another can tell an edge it already has. `inferred` and `confidence` are its provenance's.

It runs one Cypher statement per hop, no APOC, and a final one that only joins up what was reached,
so an edge between two nodes at the outer edge is drawn without reaching past it.

### Impact of a change, ranked for an agent

`POST /api/v1/impact` ([#87](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/87)) asks
the question a coding agent asks before it edits: "I am about to change this repository - what runs
on it, in which environments, who owns each of those things, and which matters most?" The answer is
built to be put into a context pack under a token budget, so it is ranked, bounded and stable: two
identical requests against an unchanged graph return byte-identical bodies.

```json
{ "repositoryKey": "github.com/acme/payments", "paths": ["infra/db.tf"], "sha": "4f1c2d9", "depth": 2, "limit": 50 }
```

Only the repository is required, named by `repositoryKey` or by `providerId`
([#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88)), with `provider` defaulting
to `github`. A provider id is consulted first, since it survives a rename. A `repositoryKey` the
repository had before a rename still names it. With neither, the request is `400` with `field:
"repositoryKey"`. `depth` is 1 to 4 (default 2) and `limit` 1 to 500 (default 50);
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

**A commit.** A `sha` restricts deployments to those whose artifact contains the change
([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)).

The sha names the Changes in the repository that it abbreviates, or that abbreviate it. It is read
in lower case, as Changes are stored. A stored sha shorter than four characters names nothing. A
Change keeps the `repositoryKey` it was written with, so the Changes written under a key the
repository had before a rename count as its own (#88).

When at least one Change matches:

- The deployments kept are those of every Artifact that CONTAINS one of the matching Changes.
- A path through any other deployment is dropped before the nearest paths are chosen. What was
  reached only through a dropped deployment, such as its environment, is dropped with it.
- The answer says `"changeScope": "applied"`. It says `applied` even when no deployment carries the
  Change yet, in which case no deployment is left.

When no Change matches, the answer says `unknown` and gives the unscoped hits. Without a sha it says
`not_requested`. The response shape did not change: `changeScope` was already a string, and
`applied` is a new value of it.

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

### Change lineage

Two questions follow the chain from a deployment to the work behind it
([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)):

| Query | Answer |
| --- | --- |
| `GET /api/v1/work-items/deployments?uri=` | Where a work item is live: `{workItem, deployments}`, most recent first. Each deployment carries its `environment`, the `artifacts` and the `changes` that carry the work item there |
| `GET /api/v1/deployments/work-items?deploymentId=` | What a deployment carried: `{deployment, lineage, changes, workItems}`. Each change names its `artifact`, and each work item lists the `changes` implementing it |

Each input is a query parameter, because a URI holds `//` and a Deployment key holds `/` and `#`.
The work item is found by its URI exactly as written.

- `lineage` is `unknown` when no artifact of the deployment contains any Change. It is not an empty
  list, which would claim the deployment carried nothing.
- A deployment whose Changes implement no work item is `known`, with no work items.
- A work item nothing has deployed is `200` with no deployments.
- A work item or deployment the graph does not hold is `404`.

Both queries need `graph:read`.

The chain is found in the registry by the node types each edge connects, not by edge name, in
`TraversalFilterBuilder.lineage()`:

- an Artifact's edge to a Deployment (`DEPLOYED_TO`),
- an Artifact's edge to a Change (`CONTAINS`),
- a Change's edge to an ExternalWorkItem (`IMPLEMENTS`),
- and the placement edges that end at an Environment (`TO_ENVIRONMENT`).

Each edge is followed in its stored direction, one plain Cypher statement per question, with no
APOC (`Neo4jChangeLineageQueries`). Closed facts are not followed. A deployment placed in several
environments is shown in the most critical one, as impact does.

`CONTAINS` propagates, with downstream read as `CONTAINED_IN`. So the blast radius of a Change
reaches the artifacts that contain it, then their deployments and environments. Nothing else about
impact changed: a repository's downstream walk does not reach its Changes, because `INTRODUCED_IN`
does not propagate.

Nodes are also addressed by key:

- A key holding `//` cannot travel in a path, because the security firewall refuses one.
- So every node can be read, replaced and deleted at `/api/v1/nodes/{type}/by-key?key=`.
- A node created with such a key is located there.
- The web interface encodes such a key as one route segment.

There is no GraphQL field for either question yet, and no screen in the web interface. Both are
answered over REST only.

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
| Change | `<repositoryKey>@<sha>`, the repository key resolved from any remote form and the sha lowercased |
| PullRequest | `<repositoryKey>/pull/<number>` |
| ExternalWorkItem | `uri`, trimmed and otherwise exactly as written: not lowercased, parsed or normalised ([ADR-0012](/adr/0012-external-work-items-are-references-not-copies)) |
| Ontology | `version` |
| SyncRun | `id` |

So `https://github.com/Acme/Payments.git`, `git@github.com:acme/payments.git` and `acme/payments`
all resolve to `github.com/acme/payments`. A uniqueness constraint on `key` is created per label for
every registry type at startup, and one over the alias's properties for every type that declares an
alias. A Repository's alias, its provider and provider id, is the one way a node's key changes: a
rename through it moves the node and records the key it left
([above](#a-repository-s-provider-id-survives-a-rename)). Merging two existing nodes that turn out to be the same thing, with
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

A key holding `//`, such as an ExternalWorkItem's URI, cannot be a path: the security firewall
refuses one. `GET`, `PUT` and `DELETE /api/v1/nodes/{type}/by-key?key=` address any node by its key
as a query parameter, and the `Location` of a created node with such a key points there.

### What is validated, and what it says

Validation is read from the registry rather than written per type. Every problem is reported at
once, each against its own field, so a form can show a user all of them rather than one per round
trip.

| Problem | Response |
| --- | --- |
| Type not in the registry | `404 {error: "unknown node type", type}` |
| Required property missing or blank | `400 {errors: [{field, message: "<name> is required"}]}` |
| Value of the wrong type | `400` with `message: "expected int"` (or the declared wire name) |
| Value outside its enum (#81) | `400` with `message: "status must be one of PENDING, IN_PROGRESS, SUCCESS, FAILED, ROLLED_BACK, CANCELLED"` |
| Value not of its format (#81) | `400` with `message: "expected url"` (or `email`, `sha256`, `arn`, ...), only when there is a value |
| Property not declared | `400` with `message: "not in ontology"` |
| Derived key already held | `409 {error: "node exists", existingId}` |
| Alias already held by another node (#88) | `409 {error: "node exists", existingId, alias}` |
| Part of an alias without the rest (#88) | `400` with `message: "provider is required with providerId"` |
| A property the server derives, such as `orgRepo` (#88) | `400` with `message: "orgRepo is derived from url and is not accepted"` |
| Update derives a different key, without the alias the node holds | `409 {error: "identity properties are immutable", fields}` |
| Update replaces the alias the node holds (#88) | `409 {error: "identity properties are immutable", fields: ["providerId"]}` |
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

A property may declare an `enum`, and `DEPENDS_ON.kind` was the first to use it — `library`, `api`,
`event`, `data` or `cmdb`, required. A free-text kind is barely worth storing: nobody can ask for "the
event-driven dependencies" if half of them say `events` and the rest say `async`. Declaring the set
is what makes the property answerable, and it is published through `GET /api/v1/ontology` so a form
offers the values rather than guessing them. Since 1.3.0 every status-like property declares one;
[Enums, and the writers they had to agree with](#enums-and-the-writers-they-had-to-agree-with) lists
them and why each set is what it is.

## Why identity cannot be edited

Because the key is derived, changing an identity property does not rename a node — it describes a
different thing. The one exception is a node renamed through its alias, a repository whose provider
id says it is the same repository under a new remote (#88). An update whose properties derive to a different key is therefore refused with the
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
([#33](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/33)), so today that means not renaming or removing.

1.1.0 ([#85](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/85)) added Change,
PullRequest, ExternalWorkItem and their five edges. It is a minor bump, because nothing was renamed
or removed. Once a 1.1.0 build has run against a graph, that graph records 1.1.0, and a 1.0.0 build
refuses to start against it. So rolling back past 1.1.0 means restoring the graph from before it, or
setting the version recorded on its `Ontology` node back by hand.

1.2.0 ([#88](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/88)) added Repository's
`provider` and `providerId`, the `alias` they form, and `previousKeys` on the provenance envelope. It
is also a minor bump, with the same caveat: a 1.1.0 build refuses to start against a graph a 1.2.0
build has recorded.

1.3.0 ([#81](https://github.com/MaximumTrainer/SdlcKnowledgeGraph/issues/81)) made the registry
self-describing: descriptions, examples, formats and deprecations on every property, example nodes and
questions on every type, and enums on the status-like properties, enforced on write. It is a minor
bump: nothing was renamed or removed, and a value stored before its enum still reads. The same
rollback caveat applies. `GET /api/v1/ontology`
returns the current registry as JSON, which is what drives the generic editing screen in the
frontend.

## Regenerating types

The registry is the only place a node type is declared. Everything else that needs to know the shape
of a node is generated from it:

| Generated file | Consumer |
| --- | --- |
| `backend/src/main/resources/graphql/schema.generated.graphqls` | GraphQL types, one `<Type>Node` per registry type, all implementing `GraphNode`, with docstrings, `@deprecated` and an enum type per enum property |
| `frontend/src/generated/ontology.ts` | Frontend interfaces with JSDoc, a union per enum property, `NODE_TYPES`, `EDGE_TYPES`, `ONTOLOGY_VERSION` |
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
