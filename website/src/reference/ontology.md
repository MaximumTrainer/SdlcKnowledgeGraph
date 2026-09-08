<!-- GENERATED FROM backend/src/main/resources/ontology/v1/ontology.json - DO NOT EDIT. Run `npm --prefix website run generate`. -->


# Ontology reference

Every type the graph may contain, as declared by the registry. This page describes ontology
**v1.0.0**. It is generated, so a type added to the registry appears here without
anyone writing a page for it.

## Node types

### Repository

A git repository, the anchor for most of the graph.

Identity: `host, org, name`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `host` | `string` | no | Host of the remote, e.g. github.com |
| `org` | `string` | no | Owning organisation or user |
| `name` | `string` | no | Repository name |
| `url` | `string` | no | Canonical https remote URL |
| `orgRepo` | `string` | yes | Legacy org/repo identifier, replaced by host+org+name in #19 |
| `defaultBranch` | `string` | yes |  |
| `topics` | `string[]` | yes |  |
| `codeowners` | `string[]` | yes |  |
| `serviceId` | `string` | no |  |
| `language` | `string` | no |  |
| `description` | `string` | no |  |

### Team

A group that owns repositories, services or infrastructure.

Identity: `name`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `string` | yes |  |
| `email` | `string` | no |  |

### Service

A running logical component, which may span more than one repository.

Identity: `name`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `string` | yes |  |
| `description` | `string` | no |  |
| `tier` | `string` | no | Criticality tier, e.g. gold |

### Pipeline

A CI/CD workflow definition.

Identity: `provider, repoKey, workflowPath`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `provider` | `string` | yes | e.g. github-actions |
| `repoKey` | `string` | no | Key of the repository holding the workflow |
| `workflowPath` | `string` | no | Path of the workflow file |
| `name` | `string` | yes |  |
| `repoId` | `string` | yes |  |
| `lastRunStatus` | `string` | no |  |

### Artifact

A built, addressable output such as a container image.

Identity: `registry, name, digest`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `registry` | `string` | no | e.g. ghcr.io |
| `name` | `string` | yes |  |
| `digest` | `string` | no | Immutable content digest, preferred for identity |
| `version` | `string` | yes |  |
| `commitSha` | `string` | no |  |
| `repoId` | `string` | no |  |
| `artifactType` | `string` | yes |  |

### Deployment

One event of putting an artifact into an environment.

Identity: `artifactKey, environmentKey, deployedAt`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `artifactKey` | `string` | no |  |
| `environmentKey` | `string` | no |  |
| `deployedAt` | `instant` | yes |  |
| `artifactId` | `string` | yes |  |
| `environmentId` | `string` | yes |  |
| `deployedBy` | `string` | no |  |
| `status` | `string` | yes |  |

### Environment

A deployment target such as staging or production.

Identity: `name`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `string` | yes |  |
| `type` | `string` | yes |  |

### CloudResource

An infrastructure object in AWS, Azure or GCP.

Identity: `provider, resourceId`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `provider` | `string` | yes | aws, azure or gcp |
| `resourceId` | `string` | no | ARN, Azure resource id or GCP asset name |
| `resourceType` | `string` | yes |  |
| `name` | `string` | yes |  |
| `region` | `string` | no |  |
| `accountId` | `string` | no |  |
| `repoId` | `string` | no |  |

### ConfigurationItem

A configuration item from a service management system such as ServiceNow.

Identity: `sourceSystem, instance, sysId`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `sourceSystem` | `string` | yes | e.g. servicenow |
| `instance` | `string` | yes | Instance the CI belongs to |
| `sysId` | `string` | yes | Identifier in the source system |
| `ciName` | `string` | yes |  |
| `ciClass` | `string` | no | e.g. cmdb_ci_service |
| `serviceId` | `string` | no |  |

### Ontology

Records which ontology version the graph was built with.

Identity: `version`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `version` | `string` | yes |  |
| `loadedAt` | `instant` | no |  |

### SyncRun

One execution of a connector, for auditing what a sync changed.

Identity: `id`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | `string` | yes |  |
| `connector` | `string` | yes |  |
| `startedAt` | `instant` | no |  |
| `finishedAt` | `instant` | no |  |
| `status` | `string` | no |  |
| `nodesUpserted` | `int` | no |  |
| `edgesUpserted` | `int` | no |  |
| `error` | `string` | no |  |

## Relationship types

An edge is stored once and read in both directions: the inverse is a traversal name, not a
second relationship.

| Type | From | To | Inverse | Description |
| --- | --- | --- | --- | --- |
| `OWNED_BY` | Repository, Service, CloudResource | Team | `OWNS` | Ownership of a repository, service or cloud resource by a team. |
| `OWNS_RESOURCE` | Repository, Service | CloudResource | `OWNED_BY_REPO` | A repository or service is responsible for a piece of infrastructure. |
| `DEPENDS_ON` | Repository, Service | Repository, Service | `DEPENDED_ON_BY` | A dependency between repositories or services. |
| `HAS_PIPELINE` | Repository | Pipeline | `PIPELINE_OF` | A repository defines a CI/CD pipeline. |
| `RELATES_TO_CI` | Repository, Service | ConfigurationItem | `CI_OF` | A repository or service corresponds to a configuration item in service management. |
| `BUILT_FROM` | Artifact | Repository | `BUILDS` | An artifact was built from a repository at a particular commit. |
| `DEPLOYED_TO` | Artifact | Deployment | `DEPLOYMENT_OF` | An artifact was the subject of a deployment. |
| `TO_ENVIRONMENT` | Deployment | Environment | `HOSTS` | A deployment targeted an environment. |
| `PROVIDES` | Repository | Service | `PROVIDED_BY` | A repository provides a running service. |

### Relationship properties

| Type | Property | Value | Required |
| --- | --- | --- | --- |
| `OWNS_RESOURCE` | `rule` | `string` | no |
| `DEPENDS_ON` | `kind` | `string` | yes |
| `DEPENDS_ON` | `manifest` | `string` | no |
| `BUILT_FROM` | `commitSha` | `string` | no |
