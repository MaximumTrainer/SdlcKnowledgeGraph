<!-- GENERATED FROM docs/api/openapi.json - DO NOT EDIT. Run `npm --prefix website run generate`. -->


# REST API reference

Generated from the OpenAPI document the application serves. A running instance offers the same
thing interactively at `/swagger-ui.html`.

| Method | Path | Summary | Responses |
| --- | --- | --- | --- |
| `GET` | `/api/v1/connectors` | List every connector, with its state and last run | 200 |
| `GET` | `/api/v1/connectors/{name}` | One connector, its descriptor and what the graph remembers about it | 200 |
| `GET` | `/api/v1/connectors/{name}/health` | Whether the source system is reachable now | 200 |
| `GET` | `/api/v1/connectors/{name}/runs` | This connector's runs, newest first | 200 |
| `POST` | `/api/v1/connectors/{name}/sync` | Ask a connector to sync now | 200 |
| `DELETE` | `/api/v1/edges` | Remove a relationship, addressed by its exact triple | 200 |
| `GET` | `/api/v1/edges` | Every relationship touching a node, under the name this end sees | 200 |
| `POST` | `/api/v1/edges` | State a relationship between two existing nodes | 200 |
| `GET` | `/api/v1/graph/cloud-resources` | Cloud resources owned by a repository | 200 |
| `GET` | `/api/v1/graph/dependencies` | Repositories this repository depends on | 200 |
| `GET` | `/api/v1/graph/dependents` | Repositories that depend on this repository | 200 |
| `GET` | `/api/v1/graph/deployments` | Deployments of artifacts built from a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/audit` | Get audit trail for a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/cloud-resources` | Get cloud resources deployed by a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/dependencies` | Get upstream dependencies of a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/dependents` | Get downstream dependents of a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/deployments` | Get all deployments from a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/impact` | Impact analysis: what is affected if this repo breaks | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/pipelines` | Get pipelines for a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/servicenow` | Get the ServiceNow CI item linked to a repository | 200 |
| `GET` | `/api/v1/graph/repositories/{repoId}/team` | Get the owning team for a repository | 200 |
| `GET` | `/api/v1/nodes/{type}` | List nodes of a type, in key order | 200 |
| `POST` | `/api/v1/nodes/{type}` | Create a node of a declared type, with a server-derived identity | 200 |
| `DELETE` | `/api/v1/nodes/{type}/{key}` | Delete a node, refusing while it still has relationships | 200 |
| `GET` | `/api/v1/nodes/{type}/{key}` | Get one node by its derived key or its full id | 200 |
| `PUT` | `/api/v1/nodes/{type}/{key}` | Replace a node's properties, keeping its identity | 200 |
| `GET` | `/api/v1/ontology` | The whole ontology: node types, edge types and their inverses | 200 |
| `GET` | `/api/v1/ontology/nodes/{type}` | A single node type | 200 |
| `GET` | `/api/v1/repositories` | List all registered repositories | 200 |
| `POST` | `/api/v1/repositories` | Register a repository in the graph | 200 |
| `GET` | `/api/v1/repositories/by-key` | Find a repository by its canonical key, in any remote notation | 200 |
| `DELETE` | `/api/v1/repositories/{id}` | Delete a repository from the graph | 200 |
| `GET` | `/api/v1/repositories/{id}` | Get a repository by ID | 200 |
| `POST` | `/api/v1/repositories/{repoId}/cloud-resources/{resourceId}` | Link repository to a cloud resource | 200 |
| `POST` | `/api/v1/repositories/{repoId}/dependencies/{depRepoId}` | Add a dependency between repositories | 200 |
| `POST` | `/api/v1/repositories/{repoId}/pipelines/{pipelineId}` | Link repository to a pipeline | 200 |
| `POST` | `/api/v1/repositories/{repoId}/servicenow/{ciId}` | Link repository to a ServiceNow CI item | 200 |
| `POST` | `/api/v1/repositories/{repoId}/teams/{teamId}` | Link repository to a team | 200 |
| `POST` | `/api/v1/webhooks/{name}` | Accept a signed webhook from a source system | 200 |
