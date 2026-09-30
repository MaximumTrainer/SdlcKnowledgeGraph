<!-- GENERATED FROM docs/api/openapi.json - DO NOT EDIT. Run `npm --prefix website run generate`. -->


# REST API reference

Generated from the OpenAPI document the application serves. A running instance offers the same
thing interactively at `/swagger-ui.html`.

| Method | Path | Summary | Responses |
| --- | --- | --- | --- |
| `GET` | `/api/v1/connectors` | List every connector, with its state, last run and freshness | 200 |
| `GET` | `/api/v1/connectors/{name}` | One connector, its descriptor and what the graph remembers about it | 200 |
| `GET` | `/api/v1/connectors/{name}/health` | Whether the source system is reachable now | 200 |
| `GET` | `/api/v1/connectors/{name}/runs` | This connector's runs, newest first | 200 |
| `POST` | `/api/v1/connectors/{name}/sync` | Ask a connector to sync now | 200 |
| `GET` | `/api/v1/deployments/work-items` | What a deployment carries: the changes its artifacts contain and the work items they implement | 200 |
| `DELETE` | `/api/v1/edges` | Remove a relationship, addressed by its exact triple | 200 |
| `GET` | `/api/v1/edges` | Every relationship touching a node, under the name this end sees | 200 |
| `POST` | `/api/v1/edges` | State a relationship between two existing nodes, as manual or as a source the caller's scopes allow | 200 |
| `GET` | `/api/v1/graph/cloud-resources` | Cloud resources owned by a repository | 200 |
| `GET` | `/api/v1/graph/dependencies` | Repositories this repository depends on | 200 |
| `GET` | `/api/v1/graph/dependents` | Repositories that depend on this repository | 200 |
| `GET` | `/api/v1/graph/deployments` | Deployments of artifacts built from a repository | 200 |
| `GET` | `/api/v1/graph/impact` | Blast radius: every node a change reaches, with the path, distance and confidence that explain it | 200 |
| `GET` | `/api/v1/graph/neighbourhood` | A bounded neighbourhood of a node, ready to draw: labelled nodes and the edges between them | 200 |
| `GET` | `/api/v1/graph/owners` | Who owns a node: its own OWNED_BY, or the owners of what it inherits ownership from | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/audit` | Get audit trail for a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/cloud-resources` | Get cloud resources deployed by a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/dependencies` | Get upstream dependencies of a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/dependents` | Get downstream dependents of a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/deployments` | Get all deployments from a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/impact` | Impact analysis: what is affected if this repo breaks. Deprecated: use /api/v1/graph/impact | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/pipelines` | Get pipelines for a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/servicenow` | Get the ServiceNow CI item linked to a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/team` | Get the owning team for a repository | 200 |
| `GET` | `/api/v1/graph/why-failed` | Why a deployment failed: its lineage, the last success before it and the dependencies deployed since | 200 |
| `POST` | `/api/v1/impact` | Impact of a change: what a change to a repository reaches, ranked, with owners and a citation per hit | 200 |
| `POST` | `/api/v1/ingest/deployment` | Record a deployment reported by the deploy pipeline | 200 |
| `POST` | `/api/v1/ingest/seed` | Record repositories, teams, pipelines and dependencies read by the dogfood seed | 200 |
| `GET` | `/api/v1/nodes/{type}` | List nodes of a type, in key order | 200 |
| `POST` | `/api/v1/nodes/{type}` | Create a node of a declared type, with a server-derived identity, as manual or a permitted source | 200 |
| `DELETE` | `/api/v1/nodes/{type}/by-key` | Delete a node addressed by its key as a query parameter (#85) | 200 |
| `GET` | `/api/v1/nodes/{type}/by-key` | Get one node by its key as a query parameter, for a key no path can carry, like a URI (#85) | 200 |
| `PUT` | `/api/v1/nodes/{type}/by-key` | Replace the properties of a node addressed by its key as a query parameter (#85) | 200 |
| `DELETE` | `/api/v1/nodes/{type}/{key}` | Delete a node, refusing while it still has relationships | 200 |
| `GET` | `/api/v1/nodes/{type}/{key}` | Get one node by its derived key or its full id | 200 |
| `PUT` | `/api/v1/nodes/{type}/{key}` | Replace a node's properties, keeping its identity | 200 |
| `GET` | `/api/v1/ontology` | The whole ontology: node types, edge types and their inverses | 200, 400 |
| `GET` | `/api/v1/ontology/nodes/{type}` | A single node type | 200 |
| `GET` | `/api/v1/repositories` | List all registered repositories, or find the one a git remote resolves to, before or after a rename | 200 |
| `POST` | `/api/v1/repositories` | Register a repository in the graph | 200 |
| `GET` | `/api/v1/repositories/by-key` | Find a repository by its canonical key, in any remote notation | 200 |
| `GET` | `/api/v1/repositories/by-provider/{provider}/{providerId}` | Find a repository by the id its provider gives it, such as GitHub's repository id | 200 |
| `DELETE` | `/api/v1/repositories/{id}` | Delete a repository from the graph | 200 |
| `GET` | `/api/v1/repositories/{id}` | Get a repository by ID | 200 |
| `POST` | `/api/v1/repositories/{repoId}/cloud-resources/{resourceId}` | Link repository to a cloud resource | 200 |
| `POST` | `/api/v1/repositories/{repoId}/dependencies/{depRepoId}` | Add a dependency between repositories | 200 |
| `POST` | `/api/v1/repositories/{repoId}/pipelines/{pipelineId}` | Link repository to a pipeline | 200 |
| `POST` | `/api/v1/repositories/{repoId}/servicenow/{ciId}` | Link repository to a ServiceNow CI item | 200 |
| `POST` | `/api/v1/repositories/{repoId}/teams/{teamId}` | Link repository to a team | 200 |
| `GET` | `/api/v1/service-principals` | List every registration, deregistered ones with their validTo | 200 |
| `POST` | `/api/v1/service-principals` | Register an identity provider client as a service principal owned by a team (users only) | 200 |
| `DELETE` | `/api/v1/service-principals/{name}` | Deregister a service principal: sets its validTo and keeps the record (users only) | 200 |
| `GET` | `/api/v1/sync-runs` | List sync runs, newest first, filtered by connector, status and start time | 200 |
| `GET` | `/api/v1/sync-runs/{id}` | One sync run in full, with its whole error and details | 200 |
| `POST` | `/api/v1/webhooks/{name}` | Accept a signed webhook from a source system | 200 |
| `GET` | `/api/v1/work-items/deployments` | Where a work item is live: every deployment of an artifact containing a change that implements it | 200 |
