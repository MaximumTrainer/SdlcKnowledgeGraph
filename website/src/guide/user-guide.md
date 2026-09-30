<!-- GENERATED FROM docs/USER-GUIDE.md - DO NOT EDIT. Run `npm --prefix website run generate`. -->

# User guide

This guide describes what the application does today, as a person using the web interface or a
program calling the API. It is deliberately limited to what is built; the [roadmap](/reference/roadmap)
says what is planned, and the [ontology guide](/guide/ontology) explains the model in depth.

If you do not have a running instance yet, [getting started](/guide/getting-started) comes first.

## What the graph holds

Everything in the graph is a **node** of a type the ontology declares, connected by **edges** of a
type the ontology declares. The current registry (ontology 1.0.0) has eleven node types: the nine
that describe software — Repository, Team, Service, Pipeline, Artifact, Deployment, Environment,
CloudResource and ConfigurationItem — plus Ontology and SyncRun, which describe the graph itself.
The nine relationship types, and which node types each may connect, are listed in the
[ontology reference](/guide/ontology#relationship-types).

Three things hold for every node, whichever type it is:

- **Its key is derived from its properties.** A repository's key is `host/org/name`, a team's is
  its lowercased name, a cloud resource's is `provider:resourceId`. You never choose a key, and
  restating the same thing twice lands on the same node rather than creating a second one.
- **Its identity cannot be edited.** Changing the properties the key is derived from would
  describe a different thing, so those properties are refused on update. Create the other thing
  instead.
- **It carries provenance.** Which source system asserted it, when, with what confidence, and
  whether it was inferred. Today every node and edge is written by a person through the interface
  or the API, so the source is always `manual` with confidence 1.0; connectors that write other
  values are [planned](/guide/adapters).

## The web interface

The interface is a Vue single-page application. Its header reads **RepoDataGraph**, the working
name the frontend package still carries, with shortcuts to Repositories, Teams and Services. Every
screen is built from the ontology the backend serves at `GET /api/v1/ontology`, so a type added to
the registry appears in the interface without a frontend change.

### Browsing nodes

Opening the interface shows the **Repository** list. The header of every page has a link per node
type in the registry, and links to the connectors and the sync run history. The graph's own
bookkeeping types (`Ontology`, `SyncRun`, `ConnectorState`) are left out of it; their lists are still
at `/nodes/<type>`. Each list shows the node's key, which links to the node's page, and the first
three properties the registry declares for that type.

The footer of every page says which ontology version and which build (the short commit) are
serving, and `read-only` when the instance refuses writes. An address the interface does not know
shows a "Page not found" page with a link back to the graph.

A list shows the first page of nodes only, fifty by default. There is no search, filtering or
sorting yet, and no page control; a type with more than fifty nodes shows the first fifty in key
order.

### Creating a node

Press **New** on a list. The form is generated from the registry: every declared property gets a
field of the right shape — text for strings, a number box for integers, a date-time picker for
instants, a checkbox for booleans, and a chip input for lists, where Enter or leaving the field
adds a value and `×` removes one. Properties the registry marks required are starred, and the form
refuses to submit until they have a value.

Fill in what you know and press **Save**. The node's page opens with the key the server derived.
Two kinds of refusal are worth knowing about:

- **A node with this identity already exists.** Something with the same derived key is already in
  the graph. Open it from the list and edit it instead.
- A field-level message such as `name is required` or `expected int`. The server validates every
  property against the registry and reports all problems at once, each under its own field.

Some types need more than their required fields to derive a key. A Repository's identity is
`host`, `org` and `name`, but the simplest way to give it is `url`: any git remote form —
`https://github.com/acme/payments`, `git@github.com:acme/payments.git`, `ssh://…` — or the bare
`acme/payments` shorthand, which assumes `github.com`. All of them derive the same key,
`github.com/acme/payments`. An Artifact needs either a `digest` or a `version` alongside its name.
A request that cannot derive a key is refused with `cannot derive identity` and the reason.

### Editing a node

On a node's page press **Edit**. The form is the same as for creation, except that the properties
forming the node's identity are disabled, with the note *Part of this node's identity, so it cannot
be changed*. Save writes the other properties back; the key and the id do not change.

### Relationships

A node's page lists its relationships under **Relationships**, grouped by the name the relationship
has *from that node's point of view*. One edge has two names: a repository sees `OWNED_BY` where
the team at the other end sees `OWNS`. Each entry links to the node at the other end, shows the
edge's own properties, and has a `×` to remove it. Removing it from either end removes the one
stored edge.

**Add relationship** opens a form offering only the relationship types the ontology allows from
this node's type. Choose one, then type in the **Target** box to search for the node at the other
end; up to ten matching keys are suggested from the first page of that type, and picking one
confirms it. A relationship with its own properties, such as `DEPENDS_ON`'s `kind`, shows those
fields too; a property with a fixed set of values is offered as a drop-down. **Add** creates the
edge and it appears immediately under its display name.

Refusals here are:

- **The ontology does not allow that relationship** — the two node types are not a permitted pair.
- **One end of that relationship does not exist.**
- **A node cannot be related to itself.**

### Deleting a node

**Delete** on a node's page removes it if nothing is related to it. If it still has edges, the
page says how many and asks whether to **Delete it and its edges**; confirming removes the node and
every edge attached to it, in one operation.

### Provenance

At the foot of a node's page, **Provenance** shows the source system, the confidence, when the fact
was ingested, whether it was inferred, who wrote it and what kind of principal they were. Through the
interface these are always `manual`, `1`, the time of the write, `no`, your account's subject, and
`user`. A fact a registered connector or agent wrote through the API names it, says `service`, and
adds **on behalf of team** with the team that owns it. A fact a scheduled connector run or an ingest
endpoint wrote, or one written before writers were recorded, says `not recorded`. Nothing new is ever
written by `anonymous`: an instance without a login accepts no writes (#118).

### Signing in

An instance with an identity provider sends you to its login page before showing anything, and back
to the page you asked for afterwards. The header then shows who you are signed in as, with
**Sign out** beside it. Your session is renewed in the background while the tab is open; if it
cannot be, the next request takes you back to the login page rather than failing. The default
compose stack signs you in with its Keycloak: `dan`, password `dan`. An instance without an identity
provider, such as the dogfood instance, has no login and can only be read: it shows no way to create,
edit or delete anything ([Read-only instances](#read-only-instances)).

### Connectors and sync runs

**Connectors** lists every connector the backend knows, enabled or not, with its source system, its
health, its last run's status and how long ago it last succeeded. A connector that has never
succeeded says `never`. An enabled connector that has gone longer than its freshness threshold
without a success is marked `stale`; hovering over the age shows the threshold
([Adapters](/guide/adapters#freshness)). **Sync now** asks an enabled connector for an incremental run.

**Sync runs** (`/sync-runs`) is the history of every connector's runs, newest first, twenty to a
page: the connector, the mode, a status chip (`RUNNING`, `SUCCESS`, `PARTIAL` or `FAILED`), when it
started (in UTC), how long it took, the nodes, edges and tombstones it wrote, and the start of its
error. The list can be narrowed by connector, by status and by when runs started; the times in the
filters are read as UTC. Choosing a run opens it in a drawer with its whole error, its watermark and
the connector's `details` as JSON. **Re-run** there asks that connector for another run, in full if
the run was a full one and incrementally otherwise, and the new run appears at the top of the list.
On a [read-only instance](#read-only-instances) the button is disabled and says why.

### What the interface does not do yet

There is no graph visualisation, no free-text search, no impact or dependency view, and no sign-in;
the interface shows every node to anyone who can reach it. The API described next can answer
some questions the interface cannot show, and the [roadmap](/reference/roadmap) tracks the rest.

## The REST API

The API is served at `http://localhost:8080/api/v1` by default, documented interactively at
`/swagger-ui.html`, and listed endpoint by endpoint in the generated
[API reference](/reference/api). Requests and responses are JSON.

Every request under `/api` and `/graphql` needs a bearer JWT from the instance's identity provider
(`Authorization: Bearer <token>`; [Authentication](/guide/auth#getting-a-token) says how to get one),
unless the instance has none and runs read-only, when reads need no token and every write is
refused ([Read-only instances](#read-only-instances)). Without a valid token the answer is
`401 {"error": "authentication required"}` with a `WWW-Authenticate: Bearer` challenge; why a token
was refused is not said. What a token may do is set by its scopes: `graph:read` for every `GET` and
GraphQL query, `graph:write` for every `POST`, `PUT`, `PATCH`, `DELETE` and GraphQL mutation. A token
without the one a request needs gets
`403 {"error": "insufficient scope", "required": ["graph:write"], "held": ["graph:read"]}`, GraphQL
included, and the web interface does not offer a user controls their scopes would not allow. A few
paths need no token and no scope: the health probes, `/actuator/info`, `/actuator/prometheus`, the ontology document, the
API documentation, the webhook receivers (which check the sender's signature) and the ingest
endpoints (which have their own bearer token). Every write records the token's subject as
`writtenBy`, and `principalType: "user"`, in its provenance.

A connector or an agent calls the API with a client-credentials token instead, once a user has
registered its client as a service principal with `POST /api/v1/service-principals`. Until then, and
after it is deregistered, it gets `403 {"error": "unregistered service principal", "clientId": ...}`.
Its writes record its registered name, `principalType: "service"` and `onBehalfOfTeam`.
[Authentication and principals](/guide/auth) covers registering one and setting up its client.

Node ids take the form `Type:key`, for example `Repository:github.com/acme/payments` or
`Team:platform`. Where a key appears in a URL path it is the raw key, slashes included, so
`GET /api/v1/nodes/Repository/github.com/acme/payments` is a valid request.

### Ontology

| Request | Result |
| --- | --- |
| `GET /api/v1/ontology` | The whole registry: version, node types with their properties and identity, edge types with their permitted ends and inverse. Cacheable; carries an `ETag`. |
| `GET /api/v1/ontology/nodes/{type}` | One node type, or `404 {error: "unknown node type", type}` |

The response is exactly the content of `backend/src/main/resources/ontology/v1/ontology.json`,
which the build generates from the registry, so a client can use that file as a fixture.

### Nodes

`/api/v1/nodes/{type}` is one surface for every type in the registry. The type in the path must be
a declared one; anything else is `404 {error: "unknown node type", type}`.

| Request | Result |
| --- | --- |
| `POST /api/v1/nodes/{type}` with `{"props": {…}}` | `201`, the node with its derived `id` and `key`, and a `Location` header |
| `GET /api/v1/nodes/{type}?limit=&cursor=` | `200 {items, nextCursor}` in key order; `limit` is 1–500, default 50; pass `nextCursor` back as `cursor` for the next page |
| `GET /api/v1/nodes/{type}/{key}` | `200` the node, or `404` |
| `PUT /api/v1/nodes/{type}/{key}` with `{"props": {…}}` | `200` the updated node, same id and key |
| `DELETE /api/v1/nodes/{type}/{key}` | `204`, or `409 {error: "node has edges", edgeCount}` while edges remain |
| `DELETE /api/v1/nodes/{type}/{key}?cascade=true` | `204`, removing the node and its edges |

A node in a response looks like this:

```json
{
  "id": "Team:platform",
  "type": "Team",
  "key": "platform",
  "props": { "name": "platform" },
  "provenance": {
    "sourceSystem": "manual",
    "sourceId": null,
    "ingestedAt": "2026-09-13T10:15:00Z",
    "observedAt": null,
    "confidence": 1.0,
    "inferred": false,
    "validFrom": "2026-09-13T10:15:00Z",
    "validTo": null,
    "syncRunId": null
  }
}
```

What is refused, and how it is reported:

| Problem | Response |
| --- | --- |
| Required property missing or blank | `400 {errors: [{field, message: "<name> is required"}]}` |
| Value of the wrong type | `400 {errors: [{field, message: "expected int"}]}` (or the declared type name) |
| Property the registry does not declare, or `id`/`key` supplied | `400 {errors: [{field, message: "not in ontology"}]}` |
| Key cannot be derived from the properties given | `400 {error: "cannot derive identity", detail}` |
| Derived key already held by another node | `409 {error: "node exists", existingId}` |
| Update that would derive a different key | `409 {error: "identity properties are immutable", fields}` |

Every property problem in a request is reported together, each against its own field.

### Edges

`/api/v1/edges` is one surface for every relationship type. Node ids travel in the body or as query
parameters rather than in the path, because a derived key can contain slashes.

| Request | Result |
| --- | --- |
| `POST /api/v1/edges` with `{type, fromId, toId, props?}` | `201` with `inverse` and both ends; `200` when the edge was already there |
| `GET /api/v1/edges?nodeId=&direction=&edgeType=` | `200 {items}`; `direction` is `in`, `out` or omitted for both; `edgeType` filters to one type |
| `DELETE /api/v1/edges?type=&fromId=&toId=` | `204`, or `404` when there is no such edge |

Each listed edge carries `type`, `inverse`, `direction`, `displayName` (the name from the asking
node's side, already resolved), `other` (the node at the far end), `props` and `provenance`.

| Problem | Response |
| --- | --- |
| Type not in the registry | `400 {error: "unknown edge type", type}` |
| Ends the ontology does not permit | `400 {error: "edge not allowed", allowed: [{from, to}]}` |
| Either end missing | `404 {error: "node not found", missing: [...]}` |
| Both ends the same node | `400 {error: "self edge", nodeId}` |
| Edge property missing, wrong type, undeclared, or outside its enum | `400 {errors: [{field, message}]}` |

`DEPENDS_ON` requires `kind`, one of `library`, `api`, `event` or `data`, and accepts `manifest`.
`OWNS_RESOURCE` accepts `rule` and `BUILT_FROM` accepts `commitSha`; both are optional.

### Traversals

These answer the questions the graph was built for, for one repository at a time. `repoId` is the
repository's key. Prefer the query-parameter form, because a key with slashes cannot be placed in a
path segment.

| Request | Result |
| --- | --- |
| `GET /api/v1/graph/dependencies?repoId=` | Repositories this one `DEPENDS_ON` |
| `GET /api/v1/graph/dependents?repoId=` | Repositories that depend on this one |
| `GET /api/v1/graph/cloud-resources?repoId=` | Cloud resources this repository `OWNS_RESOURCE` |
| `GET /api/v1/graph/deployments?repoId=` | Deployments reached through artifacts `BUILT_FROM` this repository |

`GET /api/v1/graph/repositories/{repoId}/…` offers the same four plus `team`, `pipelines`,
`servicenow`, `audit` and `impact`. `impact` is deprecated: it returns `{repoId, dependents,
cloudResources, deployments}` with a `Deprecation` header and a `Link` to its successor below.
`audit` reads from an external FactStore service configured by `FACTSTORE_URL` (default
`http://localhost:8090`) that the compose stack does not include, so it returns an empty list unless
one is running.

### Blast radius, failed deployments and owners

These walk several hops, along the edges the ontology flags, and explain every answer with a path
and a confidence ([Ontology](/guide/ontology#traversal-semantics) has the rules).

| Request | Result |
| --- | --- |
| `GET /api/v1/graph/impact?nodeId=Repository:github.com/acme/shared-lib` | Every node a change reaches, with its distance, confidence, inferred flag and path, and counts by type. `depth` 1..5 (3), `minConfidence` 0..1 (0.5), `direction` `downstream` or `upstream` |
| `GET /api/v1/graph/why-failed?deploymentId=Deployment:<key>` | The deployment's commit, repository, pipeline and environment, the last success before it, the dependencies deployed there since, and the reasons |
| `GET /api/v1/graph/owners?nodeId=CloudResource:aws:<arn>` | The teams that own a node, directly or through the repository it came from, with the path that says so |

Encode the id as a query value: a Deployment key holds `#`, which is `%23`. A malformed parameter is
`400 {error, field}` naming it; a node that does not exist is `404`.

### Impact of a change

`POST /api/v1/impact` answers what a change to a repository reaches, ranked for a coding agent's
context pack: each hit with a score, the environment it runs in, its owners and a citation of the
facts behind it, in an order that does not change between identical requests
([Ontology](/guide/ontology#impact-of-a-change-ranked-for-an-agent) has the formula).

```bash
curl -s -X POST http://localhost:8080/api/v1/impact \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"repositoryKey": "github.com/acme/payments", "paths": ["infra/db.tf"], "depth": 2, "limit": 50}'
```

`depth` is 1 to 4 (default 2), `limit` 1 to 500 (default 50). The answer says whether the `paths`
could be read against the repository's IaC and manifest index (`pathFilter`) and that a `sha` cannot
narrow it yet (`changeScope: "unknown"`). A production deployment outranks a staging one only when
the environments say which is which: give each Environment a `tier` (`production`,
`pre_production`, `development` or `other`; unset reads as `other`). It needs only `graph:read`.

### The repository endpoints

`/api/v1/repositories` predates the registry. It is kept so existing callers keep working, but
every response from it carries `Deprecation: true` and a `Link` header pointing at the successor.

| Request | Successor |
| --- | --- |
| `POST /api/v1/repositories` with `{orgRepo, defaultBranch, topics, codeowners, …}` | `POST /api/v1/nodes/Repository` |
| `GET /api/v1/repositories`, `GET`/`DELETE /api/v1/repositories/{id}` | the same under `/api/v1/nodes/Repository` |
| `POST /api/v1/repositories/{repoId}/{teams,cloud-resources,pipelines,servicenow,dependencies}/{id}` | `POST /api/v1/edges` |

New clients should not use them.

## GraphQL

`POST /graphql` serves the schema in `backend/src/main/resources/graphql/schema.graphqls`, with
GraphiQL at `/graphiql` for trying queries. It is the pre-registry surface: queries `repository`,
`repositories`, `cloudResourcesForRepo`, `dependenciesForRepo`, `dependentsForRepo`,
`deploymentsForRepo`, `teamForRepo`, `pipelinesForRepo`, `impactAnalysis` (deprecated) and
`auditEventsForRepo`, and mutations `registerRepository`, `deleteRepository`, `linkRepoToTeam`,
`linkRepoToCloudResource` and `addRepoDependency`. Those cover repositories and their immediate
neighbours only; the generic node and edge operations are available through REST. `impact(nodeId,
depth, minConfidence, direction)` and `whyDeploymentFailed(id)` answer as their REST counterparts
do, and return the generated `<Type>Node` types from `schema.generated.graphqls`, provenance
included, so select fields with `... on RepositoryNode { url }`. `changeImpact(input: {repositoryKey,
paths, sha, depth, limit})` answers as `POST /api/v1/impact` does.

## Read-only instances

An instance started with `sdlc.read-only=true` (environment variable `SDLC_READ_ONLY=true`) refuses
every write, whoever signs in. An instance with no identity provider must run this way, and does not
start otherwise: that is the anonymous read-only mode ([Authentication](/guide/auth#without-an-identity-provider-the-anonymous-read-only-mode)),
where every read needs no token and nothing can be written by nobody. The dogfood instance runs it.

- Every request other than `GET`, `HEAD` and `OPTIONS` is refused with
  `403 {"error": "this instance is read-only"}`, on every path. The refusal is deny-by-default: a
  write endpoint added later is refused until it is deliberately allowed. Two are allowed today:
  `POST /api/v1/ingest/deployment`, where the deploy pipeline reports what it deployed, and
  `POST /api/v1/ingest/seed`, where the dogfood seed records this repository. Both have their own
  bearer token and refuse anyone without it ([Adapters](/guide/adapters#self-ingestion-deployments-from-the-pipeline)).
- `POST /api/v1/impact` is answered: it is a query whose input is a body, and it writes nothing.
- `POST /graphql` is answered only when the document can be parsed and contains no mutation. A
  document that carries a mutation anywhere, whichever operation it names, is refused, and so is
  one that cannot be parsed or is larger than 256 KB.
- GraphiQL is not served (`/graphiql` returns 404), and WebSocket upgrades are refused.

The web interface still browses. Without a login it offers no way to create, edit or delete; signed
in to a read-only instance, those fail with the refusal above. **Re-run** on the sync runs page is
disabled either way. This is a posture, not access control: who may do what is decided by
[scopes](/guide/auth#scopes).

## What an instance is running

`GET /actuator/info` answers with a `deployment` block, so a person or a pipeline can ask a running
instance what it is before trusting it:

```json
{
  "deployment": {
    "commit": "0bfe55cc6c3c2ad984aeae0183e5423149acbdd8",
    "version": "0.0.1-SNAPSHOT",
    "ontologyVersion": "1.0.0",
    "profile": "docker",
    "readOnly": true
  }
}
```

`commit` is the full SHA the image was built from, stamped by the image build (`SDLC_COMMIT`); an
image built without it says `unknown`. The dogfood deploy compares it with the commit it meant to
deploy and fails if they differ.

## The Neo4j browser

The graph lives in Neo4j, and the browser at `http://localhost:7474` (user `neo4j`, password
`password` in the local stack) can query it directly. Node labels are the registry type names,
every node has a `key`, and provenance is stored flattened as `prov_`-prefixed properties. Reading
this way is fine; writing through Cypher bypasses the registry validation and identity derivation,
so anything written that way may not be what the API expects to find.
