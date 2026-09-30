<!-- GENERATED FROM backend/src/main/resources/ontology/v1/ontology.json - DO NOT EDIT. Run `npm --prefix website run generate`. -->


# Ontology reference

Every type the graph may contain, as declared by the registry. This page describes ontology
**v1.5.0**. It is generated, so a type added to the registry appears here without
anyone writing a page for it.

## Node types

### Repository

A git repository, the anchor for most of the graph.

Identity: `host, org, name`

Answers:

- Which team owns this repository?
- What does it depend on, and what depends on it?
- Which pipelines build it and where is it deployed?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `url` | `string`, format `url` | yes | The git remote, in any form; stored canonicalised as https://host/org/name | `https://github.com/acme/payments` |
| `host` | `string` | no | Host of the remote, e.g. github.com. Derived from url | `github.com` |
| `org` | `string` | no | Owning organisation or user. Derived from url | `acme` |
| `name` | `string` | no | Repository name within its organisation. Derived from url | `payments` |
| `defaultBranch` | `string` | yes | The branch changes are merged into | `main` |
| `topics` | `string[]` | yes | Topics the forge lists for the repository; may be empty | `["billing","payments"]` |
| `codeowners` | `string[]` | yes | Owners named in its CODEOWNERS file, as written there | `["@acme/payments","@acme/platform"]` |
| `serviceId` | `string` | no | **Deprecated** since 1.3.0, use `PROVIDES`. The service it provides, as an id, from before the PROVIDES edge | `payments-api` |
| `language` | `string` | no | Its main language, as the forge detects it | `Kotlin` |
| `description` | `string` | no | The one-line description the forge shows for it | `Card payments and refunds` |
| `visibility` | `string`, one of `public`, `private`, `internal` | no | Who may read it, as the forge reports it | `private` |
| `packageNames` | `string[]` | no | Package names this repository publishes | `["@acme/payments-client"]` |
| `provider` | `string`, one of `github`, `gitlab`, `other` | no | Who assigns providerId. Derived from url on github.com and gitlab.com | `github` |
| `providerId` | `string` | no | The provider's stable id for the repository, such as GitHub's numeric repository id | `123456789` |

Example:

```json
{
  "url": "https://github.com/acme/payments",
  "defaultBranch": "main",
  "topics": [
    "billing"
  ],
  "codeowners": [
    "@acme/payments"
  ],
  "language": "Kotlin",
  "visibility": "private"
}
```

### Team

A group that owns repositories, services or infrastructure.

Identity: `name`

Answers:

- What does this team own?
- Which of its repositories changed or failed recently?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `name` | `string` | yes | The team's name as its organisation spells it, or host/org/slug from GitHub | `platform` |
| `email` | `string`, format `email` | no | An address that reaches the whole team | `platform@acme.example` |

Example:

```json
{
  "name": "platform",
  "email": "platform@acme.example"
}
```

### Service

A running logical component, which may span more than one repository.

Identity: `name`

Answers:

- Which repositories provide this service?
- What does it depend on, and who owns it?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `name` | `string` | yes | The service's name, unique across the organisation | `payments-api` |
| `description` | `string` | no | What the service does, in a sentence | `Takes card payments` |
| `tier` | `string`, one of `platinum`, `gold`, `silver`, `bronze` | no | Criticality tier, platinum the most critical | `gold` |

Example:

```json
{
  "name": "payments-api",
  "description": "Takes card payments",
  "tier": "gold"
}
```

### Pipeline

A CI/CD workflow definition.

Identity: `provider, repoKey, workflowPath`

Answers:

- Which pipelines build this repository?
- Did its last run succeed?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `provider` | `string`, one of `github-actions`, `gitlab-ci`, `azure-pipelines`, `jenkins`, `other` | yes | The CI system that runs it | `github-actions` |
| `repoKey` | `string` | no | Key of the repository holding the workflow | `github.com/acme/payments` |
| `workflowPath` | `string` | no | Path of the workflow file in that repository | `.github/workflows/ci.yml` |
| `name` | `string` | yes | The workflow's display name, or its file name | `CI` |
| `repoId` | `string` | yes | **Deprecated** since 1.3.0, use `repoKey`. Key of the repository holding the workflow, from before repoKey | `github.com/acme/payments` |
| `lastRunStatus` | `string`, one of `success`, `failure`, `cancelled`, `in_progress`, `unknown` | no | How its most recent run ended, as far as the graph was told | `success` |

Example:

```json
{
  "provider": "github-actions",
  "repoKey": "github.com/acme/payments",
  "workflowPath": ".github/workflows/ci.yml",
  "name": "CI",
  "repoId": "github.com/acme/payments",
  "lastRunStatus": "success"
}
```

### Artifact

A built, addressable output such as a container image.

Identity: `registry, name, digest`

Answers:

- Which commit and repository was this artifact built from?
- Where has it been deployed?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `registry` | `string` | no | The registry host it is published to, e.g. ghcr.io | `ghcr.io` |
| `name` | `string` | yes | Its name in the registry, without the host | `acme/payments` |
| `digest` | `string`, format `sha256` | no | Immutable content digest, preferred for identity | `sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730` |
| `version` | `string` | yes | The tag or version it was published as, or its digest | `1.4.2` |
| `commitSha` | `string` | no | The commit it was built from | `9fceb02d0ae598e95dc970b74767f19372d61af8` |
| `repoId` | `string` | no | **Deprecated** since 1.3.0, use `BUILT_FROM`. Key of the repository it was built from, from before BUILT_FROM | `github.com/acme/payments` |
| `artifactType` | `string`, one of `container-image`, `jar`, `npm-package`, `python-wheel`, `helm-chart`, `terraform-module`, `binary`, `other` | yes | What kind of artifact it is | `container-image` |
| `identityQuality` | `string`, one of `digest`, `version-only` | no | How sure its key is: digest, or version-only when it is keyed name:version for want of one. Derived | `digest` |

Example:

```json
{
  "registry": "ghcr.io",
  "name": "acme/payments",
  "digest": "sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
  "version": "1.4.2",
  "commitSha": "9fceb02d0ae598e95dc970b74767f19372d61af8",
  "artifactType": "container-image"
}
```

### Deployment

One event of putting an artifact into an environment.

Identity: `artifactKey, environmentKey, deployedAt`

Answers:

- What was deployed where, and when?
- Why did this deployment fail?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `artifactKey` | `string` | no | Key of the artifact that was deployed | `ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730` |
| `environmentKey` | `string` | no | Key of the environment it was deployed to | `production` |
| `deployedAt` | `instant` | yes | When the deployment happened, in UTC | `2026-09-30T12:00:00Z` |
| `artifactId` | `string` | yes | **Deprecated** since 1.3.0, use `artifactKey`. Key of the deployed artifact, from before artifactKey | `ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730` |
| `environmentId` | `string` | yes | **Deprecated** since 1.3.0, use `environmentKey`. Key of the target environment, from before environmentKey | `production` |
| `deployedBy` | `string` | no | Who or what started the deployment | `octocat` |
| `status` | `string`, one of `PENDING`, `IN_PROGRESS`, `SUCCESS`, `FAILED`, `ROLLED_BACK`, `CANCELLED` | yes | How the deployment ended, or that it has not yet | `SUCCESS` |

Example:

```json
{
  "artifactKey": "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
  "environmentKey": "production",
  "deployedAt": "2026-09-30T12:00:00Z",
  "artifactId": "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730",
  "environmentId": "production",
  "deployedBy": "octocat",
  "status": "SUCCESS"
}
```

### Environment

A deployment target such as staging or production.

Identity: `name`

Answers:

- What is deployed to this environment?
- What would a change reaching it affect?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `name` | `string` | yes | The environment's name; prod and production are one | `production` |
| `type` | `string`, one of `development`, `test`, `staging`, `production`, `ephemeral`, `other` | yes | What kind of environment this is | `production` |
| `tier` | `string`, one of `production`, `pre_production`, `development`, `other` | no | Criticality, for ranking impact; read as other where absent | `production` |

Example:

```json
{
  "name": "production",
  "type": "production",
  "tier": "production"
}
```

### CloudResource

An infrastructure object in AWS, Azure or GCP.

Identity: `provider, resourceId`

Answers:

- Which repository or team owns this resource?
- What would a change to it affect?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `provider` | `string`, one of `aws`, `azure`, `gcp` | yes | The cloud the resource lives in | `aws` |
| `resourceId` | `string`, format `arn` when `provider` is `aws` | no | ARN, Azure resource id or GCP asset name | `arn:aws:s3:::acme-logs` |
| `resourceType` | `string` | yes | The kind of resource, in the cloud's own terms | `s3-bucket` |
| `name` | `string` | yes | The resource's human-facing name | `acme-logs` |
| `region` | `string` | no | The region it is deployed in, where it has one | `eu-west-1` |
| `accountId` | `string` | no | The account, subscription or project that holds it | `123456789012` |
| `repoId` | `string` | no | **Deprecated** since 1.3.0, use `OWNS_RESOURCE`. Key of the owning repository, from before OWNS_RESOURCE | `github.com/acme/payments` |

Example:

```json
{
  "provider": "aws",
  "resourceId": "arn:aws:s3:::acme-logs",
  "resourceType": "s3-bucket",
  "name": "acme-logs",
  "region": "eu-west-1",
  "accountId": "123456789012"
}
```

### ConfigurationItem

A configuration item from a service management system such as ServiceNow.

Identity: `sourceSystem, instance, sysId`

Answers:

- Which repository or service does this CI correspond to?
- Who supports it, and how critical is it?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `sourceSystem` | `string`, one of `servicenow`, `jsm` | yes | The service-management tool it was read from | `servicenow` |
| `instance` | `string` | yes | Instance of that tool the CI belongs to | `acme.service-now.com` |
| `sysId` | `string` | yes | Identifier of the CI in the source system | `3a7f1c2e1b2f4410a1b2c3d4e5f60718` |
| `ciName` | `string` | yes | The CI's name as the CMDB shows it | `payments-api` |
| `ciClass` | `string` | no | The CMDB class of the CI, e.g. cmdb_ci_service | `cmdb_ci_service` |
| `serviceId` | `string` | no | **Deprecated** since 1.3.0, use `RELATES_TO_CI`. The service it corresponds to, from before RELATES_TO_CI | `payments-api` |
| `operationalStatus` | `string` | no | As the CMDB reports it, e.g. Operational or Retired | `Operational` |
| `environment` | `string` | no | The environment the CMDB places it in, as written there | `Production` |
| `businessCriticality` | `string` | no | As the CMDB reports it, e.g. 1 - most critical | `1 - most critical` |
| `ownerGroup` | `string` | no | The group the CMDB names as its owner | `Payments Engineering` |
| `supportGroup` | `string` | no | The group that answers for it when it breaks | `Payments Support` |
| `number` | `string` | no | The human-facing identifier, where the CI has one | `CI0012345` |

Example:

```json
{
  "sourceSystem": "servicenow",
  "instance": "acme.service-now.com",
  "sysId": "3a7f1c2e1b2f4410a1b2c3d4e5f60718",
  "ciName": "payments-api",
  "ciClass": "cmdb_ci_service",
  "operationalStatus": "Operational",
  "supportGroup": "Payments Support"
}
```

### ChangeRequest

A change as a service-management tool records it, with its approval and window.

Identity: `sourceSystem, instance, sysId`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `sourceSystem` | `string`, one of `servicenow`, `jsm` | yes | The service-management tool it was read from | `servicenow` |
| `instance` | `string` | yes | Instance of that tool the change belongs to | `acme.service-now.com` |
| `sysId` | `string` | yes | Identifier of the change in the source system | `9d3c2b1a0f9e8d7c6b5a493827160514` |
| `number` | `string` | yes | The human-facing identifier, e.g. CHG0001 | `CHG0001234` |
| `shortDescription` | `string` | no | The change's one-line summary | `Upgrade the payments database` |
| `state` | `string` | no | As the tool reports it, e.g. Implement or Closed | `Implement` |
| `changeType` | `string`, one of `standard`, `normal`, `emergency` | no | How the change is governed: pre-approved, assessed or urgent | `normal` |
| `risk` | `string` | no | The risk the tool assessed for it, as written there | `Moderate` |
| `startDate` | `instant` | no | Start of the planned window | `2026-09-30T22:00:00Z` |
| `endDate` | `instant` | no | End of the planned window | `2026-09-30T23:00:00Z` |
| `closeCode` | `string` | no | How the change was closed, as the tool reports it | `Successful` |
| `requestedBy` | `string` | no | Who asked for the change, as the tool names them | `Ada Lovelace` |
| `assignmentGroup` | `string` | no | The group assigned to carry out the change | `Payments Engineering` |

Example:

```json
{
  "sourceSystem": "servicenow",
  "instance": "acme.service-now.com",
  "sysId": "9d3c2b1a0f9e8d7c6b5a493827160514",
  "number": "CHG0001234",
  "shortDescription": "Upgrade the payments database",
  "state": "Implement",
  "changeType": "normal",
  "startDate": "2026-09-30T22:00:00Z",
  "endDate": "2026-09-30T23:00:00Z"
}
```

### Incident

An operational failure as a service-management tool records it.

Identity: `sourceSystem, instance, sysId`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `sourceSystem` | `string`, one of `servicenow`, `jsm` | yes | The service-management tool it was read from | `servicenow` |
| `instance` | `string` | yes | Instance of that tool the incident belongs to | `acme.service-now.com` |
| `sysId` | `string` | yes | Identifier of the incident in the source system | `1f2e3d4c5b6a79880716253443526170` |
| `number` | `string` | yes | The human-facing identifier, e.g. INC0001 | `INC0012345` |
| `shortDescription` | `string` | no | The incident's one-line summary | `Card payments failing` |
| `state` | `string` | no | As the tool reports it, e.g. In Progress or Resolved | `In Progress` |
| `priority` | `string` | no | The priority the tool gives it, as written there | `1 - Critical` |
| `severity` | `string` | no | The severity the tool gives it, as written there | `1 - High` |
| `openedAt` | `instant` | no | When the incident was opened | `2026-09-30T12:05:00Z` |
| `resolvedAt` | `instant` | no | When the incident was resolved, if it has been | `2026-09-30T13:40:00Z` |
| `closeCode` | `string` | no | How the incident was closed, as the tool reports it | `Solved (Permanently)` |
| `assignmentGroup` | `string` | no | The group assigned to resolve the incident | `Payments Support` |

Example:

```json
{
  "sourceSystem": "servicenow",
  "instance": "acme.service-now.com",
  "sysId": "1f2e3d4c5b6a79880716253443526170",
  "number": "INC0012345",
  "shortDescription": "Card payments failing",
  "priority": "1 - Critical",
  "openedAt": "2026-09-30T12:05:00Z"
}
```

### Library

A third-party package a repository depends on, as its ecosystem names it.

Identity: `ecosystem, name`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `ecosystem` | `string`, one of `npm`, `maven`, `pypi`, `go` | yes | The package ecosystem; Gradle dependencies are maven | `npm` |
| `name` | `string` | yes | The package name as the manifest spells it | `vue` |
| `description` | `string` | no | What the package does, in a sentence | `The progressive JavaScript framework` |

Example:

```json
{
  "ecosystem": "npm",
  "name": "vue"
}
```

### IacFile

An infrastructure-as-code file, and the resources it names.

Identity: `repoKey, path`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `repoKey` | `string` | yes | Key of the repository holding the file | `github.com/acme/payments` |
| `path` | `string` | yes | Path within the default branch | `infra/db.tf` |
| `format` | `string`, one of `terraform`, `cdk`, `bicep`, `cloudformation` | yes | The infrastructure-as-code language of the file | `terraform` |
| `resourceRefs` | `string[]` | no | Identifiers the file names literally, for the link engine to match on | `["arn:aws:rds:eu-west-1:123456789012:db:payments"]` |

Example:

```json
{
  "repoKey": "github.com/acme/payments",
  "path": "infra/db.tf",
  "format": "terraform",
  "resourceRefs": [
    "arn:aws:rds:eu-west-1:123456789012:db:payments"
  ]
}
```

### Change

A commit range or merge in a repository, identified there by its head sha.

Identity: `repositoryKey, sha`

Answers:

- Where is this change live?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `repositoryKey` | `string` | yes | Key of the repository the change was made in, e.g. github.com/acme/payments | `github.com/acme/payments` |
| `sha` | `string` | yes | The commit the change ends at, stored in lower case | `9fceb02d0ae598e95dc970b74767f19372d61af8` |
| `baseSha` | `string` | no | The commit the change starts from, for a range | `a1b2c3d4e5f60718293a4b5c6d7e8f9012345678` |
| `title` | `string` | no | The change's summary, such as its commit subject | `Retry the payment webhook` |
| `author` | `string` | no | Who made the change, as the forge names them | `octocat` |
| `committedAt` | `instant` | yes | When the change was committed | `2026-09-30T10:00:00Z` |
| `url` | `string`, format `url` | no | Where the change can be read, e.g. the commit or compare page | `https://github.com/acme/payments/commit/9fceb02d0ae598e95dc970b74767f19372d61af8` |

Example:

```json
{
  "repositoryKey": "github.com/acme/payments",
  "sha": "9fceb02d0ae598e95dc970b74767f19372d61af8",
  "title": "Retry the payment webhook",
  "author": "octocat",
  "committedAt": "2026-09-30T10:00:00Z",
  "url": "https://github.com/acme/payments/commit/9fceb02d0ae598e95dc970b74767f19372d61af8"
}
```

### PullRequest

A proposal to merge a change into a repository, as the forge records it.

Identity: `repositoryKey, number`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `repositoryKey` | `string` | yes | Key of the repository the pull request targets | `github.com/acme/payments` |
| `number` | `int` | yes | The forge's number for it within the repository | `42` |
| `url` | `string`, format `url` | yes | Where the pull request can be read on the forge | `https://github.com/acme/payments/pull/42` |
| `title` | `string` | no | The pull request's title | `Retry the payment webhook` |
| `state` | `string`, one of `open`, `merged`, `closed` | no | Whether it is open, merged or closed unmerged | `merged` |
| `mergedAt` | `instant` | no | When it was merged, if it has been | `2026-09-30T11:00:00Z` |
| `branch` | `string` | no | The head branch, e.g. chorus/CH-42-retry-webhook | `chorus/CH-42-retry-webhook` |

Example:

```json
{
  "repositoryKey": "github.com/acme/payments",
  "number": 42,
  "url": "https://github.com/acme/payments/pull/42",
  "title": "Retry the payment webhook",
  "state": "merged",
  "mergedAt": "2026-09-30T11:00:00Z",
  "branch": "chorus/CH-42-retry-webhook"
}
```

### ExternalWorkItem

A task, ticket or issue in the system that owns it, referred to by its URI.

Identity: `uri`

Answers:

- Where is this work item live?

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `uri` | `string` | yes | e.g. chorus://task/01J... or https://acme.atlassian.net/browse/PAY-42 | `https://acme.atlassian.net/browse/PAY-42` |
| `system` | `string`, one of `chorus`, `jira`, `linear`, `github`, `other` | yes | The system that owns the work item | `jira` |
| `externalKey` | `string` | no | Its human-facing identifier there, e.g. CH-42 | `PAY-42` |
| `title` | `string` | no | The work item's title in the owning system | `Retry the payment webhook` |

Example:

```json
{
  "uri": "https://acme.atlassian.net/browse/PAY-42",
  "system": "jira",
  "externalKey": "PAY-42",
  "title": "Retry the payment webhook"
}
```

### Ontology

Records which ontology version the graph was built with.

Identity: `version`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `version` | `string`, format `semver` | yes | The ontology version, as version.yaml declares it | `1.4.0` |
| `loadedAt` | `instant` | no | When a build first recorded this version | `2026-09-30T12:00:00Z` |

Example:

```json
{
  "version": "1.4.0",
  "loadedAt": "2026-09-30T12:00:00Z"
}
```

### SyncRun

One execution of a connector, for auditing what a sync changed.

Identity: `id`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `id` | `string` | yes | The run's id, stamped on the provenance of what it wrote | `0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b` |
| `connector` | `string` | yes | The connector that ran, by its registered name | `github` |
| `sourceSystem` | `string` | no | What this run stamped on the provenance of everything it wrote | `github` |
| `mode` | `string`, one of `FULL`, `INCREMENTAL`, `WEBHOOK` | no | Whether it read everything, what changed, or one delivery | `INCREMENTAL` |
| `sourceId` | `string` | no | The source system's id for the delivery that caused this run | `72d3162e-cc78-11e3-81ab-4c9367dc0958` |
| `startedAt` | `instant` | no | When the run started | `2026-09-30T12:00:00Z` |
| `finishedAt` | `instant` | no | When the run finished, if it has | `2026-09-30T12:01:30Z` |
| `status` | `string`, one of `RUNNING`, `SUCCESS`, `PARTIAL`, `FAILED` | no | Whether the run is going, worked, partly worked or failed | `SUCCESS` |
| `nodesUpserted` | `int` | no | Nodes the run created or updated | `42` |
| `edgesUpserted` | `int` | no | Edges the run created or updated | `57` |
| `tombstones` | `int` | no | Facts this run closed because the source stopped reporting them | `0` |
| `watermark` | `instant` | no | Where the next incremental run should start | `2026-09-30T12:00:00Z` |
| `error` | `string` | no | Why the run failed or partly failed, where it did | `GitHub returned 502 for acme/payments` |

Example:

```json
{
  "id": "0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b",
  "connector": "github",
  "sourceSystem": "github",
  "mode": "INCREMENTAL",
  "startedAt": "2026-09-30T12:00:00Z",
  "finishedAt": "2026-09-30T12:01:30Z",
  "status": "SUCCESS",
  "nodesUpserted": 42,
  "edgesUpserted": 57,
  "tombstones": 0
}
```

### ConnectorState

What the graph remembers about a connector between runs.

Identity: `connector`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `connector` | `string` | yes | The connector this state belongs to | `github` |
| `watermark` | `instant` | no | Reported by the last successful run | `2026-09-30T12:00:00Z` |
| `lastRunId` | `string` | no | The last scheduled or manual run to finish, whatever its status | `0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b` |
| `lastStatus` | `string`, one of `SUCCESS`, `PARTIAL`, `FAILED` | no | That run's status; the same as lastRunStatus, kept for states written before it | `SUCCESS` |
| `lastRunStatus` | `string`, one of `SUCCESS`, `PARTIAL`, `FAILED` | no | How the last run to finish ended | `SUCCESS` |
| `lastFinishedAt` | `instant` | no | When the last run to finish finished | `2026-09-30T12:01:30Z` |
| `lastSuccessAt` | `instant` | no | When the last successful run finished | `2026-09-30T12:01:30Z` |
| `consecutiveFailures` | `int` | no | Runs that ended PARTIAL or FAILED since the last SUCCESS | `0` |

Example:

```json
{
  "connector": "github",
  "watermark": "2026-09-30T12:00:00Z",
  "lastRunId": "0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b",
  "lastStatus": "SUCCESS",
  "lastRunStatus": "SUCCESS",
  "lastFinishedAt": "2026-09-30T12:01:30Z",
  "lastSuccessAt": "2026-09-30T12:01:30Z",
  "consecutiveFailures": 0
}
```

### NodeVersion

The values a node held for an interval before they were replaced.

Identity: `versionOf, since`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `versionOf` | `string` | yes | The id of the node these values belonged to | `Repository:github.com/acme/payments` |
| `since` | `instant` | yes | When the node began to hold these values | `2026-01-01T00:00:00Z` |
| `until` | `instant` | yes | When a write replaced them, or the node was retired | `2026-02-01T00:00:00Z` |
| `retired` | `boolean` | no | True when the values ended because the node was retired rather than changed | `false` |
| `retiredReason` | `string`, one of `source-deleted`, `source-retired`, `missing-from-sync`, `manual`, `merged` | no | Why the node was retired, where it was | `missing-from-sync` |

Example:

```json
{
  "versionOf": "Repository:github.com/acme/payments",
  "since": "2026-01-01T00:00:00Z",
  "until": "2026-02-01T00:00:00Z",
  "retired": false
}
```

### OntologyMigration

A versioned migration of the graph's data, and when it was applied.

Identity: `version`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `version` | `string`, format `semver` | yes | The ontology version the migration brings the graph to | `1.4.0` |
| `name` | `string` | yes | The migration's name, from its file name | `rename_ci_legacy_name` |
| `checksum` | `string` | yes | SHA-256 of the file that was applied | `9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08` |
| `appliedAt` | `instant` | yes | When it was applied or recorded | `2026-09-30T12:00:00Z` |
| `durationMs` | `int` | no | How long it took to run, in milliseconds | `12` |
| `baseline` | `boolean` | no | True when recorded without running, because the graph never needed it | `false` |

Example:

```json
{
  "version": "1.4.0",
  "name": "rename_ci_legacy_name",
  "checksum": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "appliedAt": "2026-09-30T12:00:00Z",
  "durationMs": 12,
  "baseline": false
}
```

### ServicePrincipal

A connector or agent registered as a principal of its own, owned by a team.

Identity: `name`

| Property | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| `name` | `string` | yes | The client id its tokens carry (azp, or client_id) | `payments-deployer` |
| `ownedBy` | `string` | yes | Key of the Team that answers for it; recorded on its writes as onBehalfOfTeam | `platform` |
| `description` | `string` | no | What the principal is for, in a sentence | `Reports deployments of the payments service` |

Example:

```json
{
  "name": "payments-deployer",
  "ownedBy": "platform",
  "description": "Reports deployments of the payments service"
}
```

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
| `INTRODUCED_IN` | Change | Repository | `HAS_CHANGE` | A change was made in a repository. |
| `MERGES` | PullRequest | Change | `MERGED_BY` | A pull request merged a change. |
| `CONTAINS` | Artifact | Change | `CONTAINED_IN` | An artifact was built with a change in it. |
| `IMPLEMENTS` | Change | ExternalWorkItem | `IMPLEMENTED_BY` | A change implements a work item in the system that owns it. |
| `TRACKED_IN` | ExternalWorkItem | Team | `TRACKS` | A work item is tracked by a team. |
| `PRODUCED` | SyncRun | Repository, Team, Service, Pipeline, Artifact, Deployment, Environment, CloudResource, ConfigurationItem, Library, IacFile, ChangeRequest, Incident, Change, PullRequest, ExternalWorkItem | `PRODUCED_BY` | A sync run asserted this node. |

### Relationship properties

| Type | Property | Value | Required | Description | Example |
| --- | --- | --- | --- | --- | --- |
| `OWNED_BY` | `pathPatterns` | `string[]` | no | CODEOWNERS patterns the owner was named against, where ownership came from a file | `["*","/docs/"]` |
| `OWNS_RESOURCE` | `rule` | `string`, one of `manual`, `tag`, `deployment`, `iac`, `naming` | no | What the ownership rests on: stated by hand, or the link rule that proposed it | `tag` |
| `DEPENDS_ON` | `kind` | `string`, one of `library`, `api`, `event`, `data`, `cmdb` | yes | What sort of dependency this is | `library` |
| `DEPENDS_ON` | `manifest` | `string` | no | File the dependency was read from, with its path from the repository root in a monorepo | `frontend/package.json` |
| `DEPENDS_ON` | `version` | `string` | no | The version the manifest asks for, as written | `^3.5.0` |
| `DEPENDS_ON` | `scope` | `string`, one of `runtime`, `dev` | no | Whether the dependency is needed to run or only to build | `runtime` |
| `DEPENDS_ON` | `relType` | `string` | no | The source system's own name for the relationship, e.g. a CMDB's 'Depends on::Used by' | `Depends on::Used by` |
| `BUILT_FROM` | `commitSha` | `string` | no | The commit the artifact was built from | `9fceb02d0ae598e95dc970b74767f19372d61af8` |
| `PROVIDES` | `path` | `string` | no | The directory the service is built from, relative to the repository root; absent for the whole repository | `services/billing` |

## Source systems

What `provenance.sourceSystem` may name. A write through the API naming any source but
`manual` also needs that source's scope; see [Authentication](/guide/auth#source-scopes).

| Source | Scope to write as it | Description |
| --- | --- | --- |
| `manual` | `graph:write` | Stated through the API by the principal that made the write. Needs graph:write only |
| `github` | `graph:write:github` | Repositories, teams, manifests and ownership read from GitHub by the GitHub connector |
| `github-actions` | `graph:write:github-actions` | Pipelines, artifacts and deployments reported by GitHub Actions workflows (POST /api/v1/ingest/deployment) |
| `servicenow` | `graph:write:servicenow` | Configuration items, change requests and incidents read from ServiceNow by the ServiceNow connector |
| `aws` | `graph:write:aws` | Cloud resources read from AWS. Declared for the AWS connector; none ships yet |
| `dogfood-seed` | `graph:write:dogfood-seed` | What the dogfood seed job reads from this repository (POST /api/v1/ingest/seed) |
| `sdlc-knowledge-graph` | `graph:write:sdlc-knowledge-graph` | The graph's record of itself: the sync runs and connector states it keeps |
