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
| `url` | `string` | yes | The git remote, in any form; stored canonicalised as https://host/org/name |
| `host` | `string` | no | Host of the remote, e.g. github.com. Derived from url |
| `org` | `string` | no | Owning organisation or user. Derived from url |
| `name` | `string` | no | Repository name. Derived from url |
| `defaultBranch` | `string` | yes |  |
| `topics` | `string[]` | yes |  |
| `codeowners` | `string[]` | yes |  |
| `serviceId` | `string` | no |  |
| `language` | `string` | no |  |
| `description` | `string` | no |  |
| `visibility` | `string` | no | public, private or internal, as the forge reports it |
| `packageNames` | `string[]` | no | Package names this repository publishes |

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
| `operationalStatus` | `string` | no | As the CMDB reports it, e.g. Operational or Retired |
| `environment` | `string` | no |  |
| `businessCriticality` | `string` | no | e.g. 1 - most critical |
| `ownerGroup` | `string` | no |  |
| `supportGroup` | `string` | no |  |
| `number` | `string` | no | The human-facing identifier, where the CI has one |

### ChangeRequest

A change as a service-management tool records it, with its approval and window.

Identity: `sourceSystem, instance, sysId`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `sourceSystem` | `string` | yes | e.g. servicenow |
| `instance` | `string` | yes |  |
| `sysId` | `string` | yes |  |
| `number` | `string` | yes | The human-facing identifier, e.g. CHG0001 |
| `shortDescription` | `string` | no |  |
| `state` | `string` | no | As the tool reports it, e.g. Implement or Closed |
| `changeType` | `string` | no |  |
| `risk` | `string` | no |  |
| `startDate` | `instant` | no | Start of the planned window |
| `endDate` | `instant` | no | End of the planned window |
| `closeCode` | `string` | no |  |
| `requestedBy` | `string` | no |  |
| `assignmentGroup` | `string` | no |  |

### Incident

An operational failure as a service-management tool records it.

Identity: `sourceSystem, instance, sysId`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `sourceSystem` | `string` | yes | e.g. servicenow |
| `instance` | `string` | yes |  |
| `sysId` | `string` | yes |  |
| `number` | `string` | yes | The human-facing identifier, e.g. INC0001 |
| `shortDescription` | `string` | no |  |
| `state` | `string` | no |  |
| `priority` | `string` | no |  |
| `severity` | `string` | no |  |
| `openedAt` | `instant` | no |  |
| `resolvedAt` | `instant` | no |  |
| `closeCode` | `string` | no |  |
| `assignmentGroup` | `string` | no |  |

### Library

A third-party package a repository depends on, as its ecosystem names it.

Identity: `ecosystem, name`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `ecosystem` | `string` | yes | npm, maven, pypi, go or gradle |
| `name` | `string` | yes | The package name as the manifest spells it |
| `description` | `string` | no |  |

### IacFile

An infrastructure-as-code file, and the resources it names.

Identity: `repoKey, path`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `repoKey` | `string` | yes | Key of the repository holding the file |
| `path` | `string` | yes | Path within the default branch |
| `format` | `string` | yes |  |
| `resourceRefs` | `string[]` | no | Identifiers the file names literally, for the link engine to match on |

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
| `sourceSystem` | `string` | no | What this run stamped on the provenance of everything it wrote |
| `mode` | `string` | no | FULL, INCREMENTAL or WEBHOOK |
| `sourceId` | `string` | no | The source system's id for the delivery that caused this run |
| `startedAt` | `instant` | no |  |
| `finishedAt` | `instant` | no |  |
| `status` | `string` | no | RUNNING, SUCCESS, PARTIAL or FAILED |
| `nodesUpserted` | `int` | no |  |
| `edgesUpserted` | `int` | no |  |
| `tombstones` | `int` | no | Facts this run closed because the source stopped reporting them |
| `watermark` | `instant` | no | Where the next incremental run should start |
| `error` | `string` | no |  |

### ConnectorState

What the graph remembers about a connector between runs.

Identity: `connector`

| Property | Type | Required | Description |
| --- | --- | --- | --- |
| `connector` | `string` | yes |  |
| `watermark` | `instant` | no | Reported by the last successful run |
| `lastRunId` | `string` | no |  |
| `lastStatus` | `string` | no |  |
| `lastFinishedAt` | `instant` | no |  |

## Relationship types

An edge is stored once and read in both directions: the inverse is a traversal name, not a
second relationship.

| Type | From | To | Inverse | Description |
| --- | --- | --- | --- | --- |
| `OWNED_BY` | Repository, Service, CloudResource | Team | `OWNS` | Ownership of a repository, service or cloud resource by a team. |
| `OWNS_RESOURCE` | Repository, Service | CloudResource | `OWNED_BY_REPO` | A repository or service is responsible for a piece of infrastructure. |
| `DEPENDS_ON` | Repository, Service, ConfigurationItem | Repository, Service, Library, ConfigurationItem | `DEPENDED_ON_BY` | A dependency between repositories, services or third-party libraries. |
| `CONTAINS_IAC` | Repository | IacFile | `IAC_IN` | A repository holds an infrastructure-as-code file. |
| `HAS_PIPELINE` | Repository | Pipeline | `PIPELINE_OF` | A repository defines a CI/CD pipeline. |
| `RELATES_TO_CI` | Repository, Service | ConfigurationItem | `CI_OF` | A repository or service corresponds to a configuration item in service management. |
| `AFFECTS` | ChangeRequest, Incident | ConfigurationItem, Service, Repository | `AFFECTED_BY` | A change or an incident concerns a configuration item, service or repository. |
| `CAUSED_BY` | Incident | ChangeRequest, Deployment | `CAUSED` | An incident is attributed to a change. |
| `BUILT_FROM` | Artifact | Repository | `BUILDS` | An artifact was built from a repository at a particular commit. |
| `DEPLOYED_TO` | Artifact | Deployment | `DEPLOYMENT_OF` | An artifact was the subject of a deployment. |
| `TO_ENVIRONMENT` | Deployment | Environment | `HOSTS` | A deployment targeted an environment. |
| `PROVIDES` | Repository | Service | `PROVIDED_BY` | A repository provides a running service. |
| `PRODUCED` | SyncRun | Repository, Team, Service, Pipeline, Artifact, Deployment, Environment, CloudResource, ConfigurationItem, Library, IacFile, ChangeRequest, Incident | `PRODUCED_BY` | A sync run asserted this node. |

### Relationship properties

| Type | Property | Value | Required |
| --- | --- | --- | --- |
| `OWNED_BY` | `pathPatterns` | `string[]` | no |
| `OWNS_RESOURCE` | `rule` | `string` | no |
| `DEPENDS_ON` | `kind` | `string` | yes |
| `DEPENDS_ON` | `manifest` | `string` | no |
| `DEPENDS_ON` | `version` | `string` | no |
| `DEPENDS_ON` | `scope` | `string` | no |
| `DEPENDS_ON` | `relType` | `string` | no |
| `BUILT_FROM` | `commitSha` | `string` | no |
