// GENERATED FROM ontology/v1 - DO NOT EDIT
// Run ./gradlew generateOntology after changing the registry.

export const ONTOLOGY_VERSION = '1.7.0'

export interface Provenance {
  sourceSystem: string
  sourceId: string | null
  ingestedAt: string
  observedAt: string | null
  confidence: number
  inferred: boolean
  validFrom: string
  validTo: string | null
  syncRunId: string | null
  writtenBy: string | null
  principalType: string | null
  onBehalfOfTeam: string | null
  previousKeys: string[] | null
}

export type RepositoryVisibility = 'public' | 'private' | 'internal'

export type RepositoryProvider = 'github' | 'gitlab' | 'other'

/** A git repository, the anchor for most of the graph. */
export interface Repository {
  id: string
  /**
   * The git remote, in any form; stored canonicalised as https://host/org/name
   * @example "https://github.com/acme/payments"
   */
  url: string
  /**
   * Host of the remote, e.g. github.com. Derived from url
   * @example "github.com"
   */
  host?: string
  /**
   * Owning organisation or user. Derived from url
   * @example "acme"
   */
  org?: string
  /**
   * Repository name within its organisation. Derived from url
   * @example "payments"
   */
  name?: string
  /**
   * The branch changes are merged into
   * @example "main"
   */
  defaultBranch: string
  /**
   * Topics the forge lists for the repository; may be empty
   * @example ["billing","payments"]
   */
  topics: string[]
  /**
   * Owners named in its CODEOWNERS file, as written there
   * @example ["@acme/payments","@acme/platform"]
   */
  codeowners: string[]
  /**
   * The service it provides, as an id, from before the PROVIDES edge
   * @example "payments-api"
   * @deprecated since 1.3.0, replaced by PROVIDES
   */
  serviceId?: string
  /**
   * Its main language, as the forge detects it
   * @example "Kotlin"
   */
  language?: string
  /**
   * The one-line description the forge shows for it
   * @example "Card payments and refunds"
   */
  description?: string
  /**
   * Who may read it, as the forge reports it
   * @example "private"
   */
  visibility?: RepositoryVisibility
  /**
   * Package names this repository publishes
   * @example ["@acme/payments-client"]
   */
  packageNames?: string[]
  /**
   * Key of the repository this one was forked from, where it is a fork
   * @example "github.com/upstream/payments"
   */
  forkOf?: string
  /**
   * Who assigns providerId. Derived from url on github.com and gitlab.com
   * @example "github"
   */
  provider?: RepositoryProvider
  /**
   * The provider's stable id for the repository, such as GitHub's numeric repository id
   * @example "123456789"
   */
  providerId?: string
}

/** A group that owns repositories, services or infrastructure. */
export interface Team {
  id: string
  /**
   * The team's name as its organisation spells it, or host/org/slug from GitHub
   * @example "platform"
   */
  name: string
  /**
   * An address that reaches the whole team
   * @example "platform@acme.example"
   */
  email?: string
}

export type ServiceTier = 'platinum' | 'gold' | 'silver' | 'bronze'

/** A running logical component, which may span more than one repository. */
export interface Service {
  id: string
  /**
   * The service's name, unique across the organisation
   * @example "payments-api"
   */
  name: string
  /**
   * What the service does, in a sentence
   * @example "Takes card payments"
   */
  description?: string
  /**
   * Criticality tier, platinum the most critical
   * @example "gold"
   */
  tier?: ServiceTier
}

export type PipelineProvider =
  'github-actions' | 'gitlab-ci' | 'azure-pipelines' | 'jenkins' | 'other'

export type PipelineLastRunStatus = 'success' | 'failure' | 'cancelled' | 'in_progress' | 'unknown'

/** A CI/CD workflow definition. */
export interface Pipeline {
  id: string
  /**
   * The CI system that runs it
   * @example "github-actions"
   */
  provider: PipelineProvider
  /**
   * Key of the repository holding the workflow
   * @example "github.com/acme/payments"
   */
  repoKey?: string
  /**
   * Path of the workflow file in that repository
   * @example ".github/workflows/ci.yml"
   */
  workflowPath?: string
  /**
   * The workflow's display name, or its file name
   * @example "CI"
   */
  name: string
  /**
   * Key of the repository holding the workflow, from before repoKey
   * @example "github.com/acme/payments"
   * @deprecated since 1.3.0, replaced by repoKey
   */
  repoId: string
  /**
   * How its most recent run ended, as far as the graph was told
   * @example "success"
   */
  lastRunStatus?: PipelineLastRunStatus
}

export type ArtifactArtifactType =
  | 'container-image'
  | 'jar'
  | 'npm-package'
  | 'python-wheel'
  | 'helm-chart'
  | 'terraform-module'
  | 'binary'
  | 'other'

export type ArtifactIdentityQuality = 'digest' | 'version-only'

/** A built, addressable output such as a container image. */
export interface Artifact {
  id: string
  /**
   * The registry host it is published to, e.g. ghcr.io
   * @example "ghcr.io"
   */
  registry?: string
  /**
   * Its name in the registry, without the host
   * @example "acme/payments"
   */
  name: string
  /**
   * Immutable content digest, preferred for identity
   * @example "sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730"
   */
  digest?: string
  /**
   * The tag or version it was published as, or its digest
   * @example "1.4.2"
   */
  version: string
  /**
   * The commit it was built from
   * @example "9fceb02d0ae598e95dc970b74767f19372d61af8"
   */
  commitSha?: string
  /**
   * Key of the repository it was built from, from before BUILT_FROM
   * @example "github.com/acme/payments"
   * @deprecated since 1.3.0, replaced by BUILT_FROM
   */
  repoId?: string
  /**
   * What kind of artifact it is
   * @example "container-image"
   */
  artifactType: ArtifactArtifactType
  /**
   * How sure its key is: digest, or version-only when it is keyed name:version for want of one. Derived
   * @example "digest"
   */
  identityQuality?: ArtifactIdentityQuality
}

export type DeploymentStatus =
  'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'ROLLED_BACK' | 'CANCELLED'

/** One event of putting an artifact into an environment. */
export interface Deployment {
  id: string
  /**
   * Key of the artifact that was deployed
   * @example "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730"
   */
  artifactKey?: string
  /**
   * Key of the environment it was deployed to
   * @example "production"
   */
  environmentKey?: string
  /**
   * When the deployment happened, in UTC
   * @example "2026-09-30T12:00:00Z"
   */
  deployedAt: string
  /**
   * Key of the deployed artifact, from before artifactKey
   * @example "ghcr.io/acme/payments@sha256:7d865e959b2466918c9863afca942d0fb89d7c9ac0c99bafc3749504ded97730"
   * @deprecated since 1.3.0, replaced by artifactKey
   */
  artifactId: string
  /**
   * Key of the target environment, from before environmentKey
   * @example "production"
   * @deprecated since 1.3.0, replaced by environmentKey
   */
  environmentId: string
  /**
   * Who or what started the deployment
   * @example "octocat"
   */
  deployedBy?: string
  /**
   * How the deployment ended, or that it has not yet
   * @example "SUCCESS"
   */
  status: DeploymentStatus
}

export type EnvironmentType =
  'development' | 'test' | 'staging' | 'production' | 'ephemeral' | 'other'

export type EnvironmentTier = 'production' | 'pre_production' | 'development' | 'other'

/** A deployment target such as staging or production. */
export interface Environment {
  id: string
  /**
   * The environment's name; prod and production are one
   * @example "production"
   */
  name: string
  /**
   * What kind of environment this is
   * @example "production"
   */
  type: EnvironmentType
  /**
   * Criticality, for ranking impact; read as other where absent
   * @example "production"
   */
  tier?: EnvironmentTier
}

export type CloudResourceProvider = 'aws' | 'azure' | 'gcp'

/** An infrastructure object in AWS, Azure or GCP. */
export interface CloudResource {
  id: string
  /**
   * The cloud the resource lives in
   * @example "aws"
   */
  provider: CloudResourceProvider
  /**
   * ARN, Azure resource id or GCP asset name
   * @example "arn:aws:s3:::acme-logs"
   */
  resourceId?: string
  /**
   * The kind of resource, in the cloud's own terms
   * @example "s3-bucket"
   */
  resourceType: string
  /**
   * The resource's human-facing name
   * @example "acme-logs"
   */
  name: string
  /**
   * The region it is deployed in, where it has one
   * @example "eu-west-1"
   */
  region?: string
  /**
   * The account, subscription or project that holds it
   * @example "123456789012"
   */
  accountId?: string
  /**
   * Key of the owning repository, from before OWNS_RESOURCE
   * @example "github.com/acme/payments"
   * @deprecated since 1.3.0, replaced by OWNS_RESOURCE
   */
  repoId?: string
}

export type ConfigurationItemSourceSystem = 'servicenow' | 'jsm'

/** A configuration item from a service management system such as ServiceNow. */
export interface ConfigurationItem {
  id: string
  /**
   * The service-management tool it was read from
   * @example "servicenow"
   */
  sourceSystem: ConfigurationItemSourceSystem
  /**
   * Instance of that tool the CI belongs to
   * @example "acme.service-now.com"
   */
  instance: string
  /**
   * Identifier of the CI in the source system
   * @example "3a7f1c2e1b2f4410a1b2c3d4e5f60718"
   */
  sysId: string
  /**
   * The CI's name as the CMDB shows it
   * @example "payments-api"
   */
  ciName: string
  /**
   * The CMDB class of the CI, e.g. cmdb_ci_service
   * @example "cmdb_ci_service"
   */
  ciClass?: string
  /**
   * The service it corresponds to, from before RELATES_TO_CI
   * @example "payments-api"
   * @deprecated since 1.3.0, replaced by RELATES_TO_CI
   */
  serviceId?: string
  /**
   * As the CMDB reports it, e.g. Operational or Retired
   * @example "Operational"
   */
  operationalStatus?: string
  /**
   * The environment the CMDB places it in, as written there
   * @example "Production"
   */
  environment?: string
  /**
   * As the CMDB reports it, e.g. 1 - most critical
   * @example "1 - most critical"
   */
  businessCriticality?: string
  /**
   * The group the CMDB names as its owner
   * @example "Payments Engineering"
   */
  ownerGroup?: string
  /**
   * The group that answers for it when it breaks
   * @example "Payments Support"
   */
  supportGroup?: string
  /**
   * The human-facing identifier, where the CI has one
   * @example "CI0012345"
   */
  number?: string
}

export type ChangeRequestSourceSystem = 'servicenow' | 'jsm'

export type ChangeRequestChangeType = 'standard' | 'normal' | 'emergency'

/** A change as a service-management tool records it, with its approval and window. */
export interface ChangeRequest {
  id: string
  /**
   * The service-management tool it was read from
   * @example "servicenow"
   */
  sourceSystem: ChangeRequestSourceSystem
  /**
   * Instance of that tool the change belongs to
   * @example "acme.service-now.com"
   */
  instance: string
  /**
   * Identifier of the change in the source system
   * @example "9d3c2b1a0f9e8d7c6b5a493827160514"
   */
  sysId: string
  /**
   * The human-facing identifier, e.g. CHG0001
   * @example "CHG0001234"
   */
  number: string
  /**
   * The change's one-line summary
   * @example "Upgrade the payments database"
   */
  shortDescription?: string
  /**
   * As the tool reports it, e.g. Implement or Closed
   * @example "Implement"
   */
  state?: string
  /**
   * How the change is governed: pre-approved, assessed or urgent
   * @example "normal"
   */
  changeType?: ChangeRequestChangeType
  /**
   * The risk the tool assessed for it, as written there
   * @example "Moderate"
   */
  risk?: string
  /**
   * Start of the planned window
   * @example "2026-09-30T22:00:00Z"
   */
  startDate?: string
  /**
   * End of the planned window
   * @example "2026-09-30T23:00:00Z"
   */
  endDate?: string
  /**
   * How the change was closed, as the tool reports it
   * @example "Successful"
   */
  closeCode?: string
  /**
   * Who asked for the change, as the tool names them
   * @example "Ada Lovelace"
   */
  requestedBy?: string
  /**
   * The group assigned to carry out the change
   * @example "Payments Engineering"
   */
  assignmentGroup?: string
}

export type IncidentSourceSystem = 'servicenow' | 'jsm'

/** An operational failure as a service-management tool records it. */
export interface Incident {
  id: string
  /**
   * The service-management tool it was read from
   * @example "servicenow"
   */
  sourceSystem: IncidentSourceSystem
  /**
   * Instance of that tool the incident belongs to
   * @example "acme.service-now.com"
   */
  instance: string
  /**
   * Identifier of the incident in the source system
   * @example "1f2e3d4c5b6a79880716253443526170"
   */
  sysId: string
  /**
   * The human-facing identifier, e.g. INC0001
   * @example "INC0012345"
   */
  number: string
  /**
   * The incident's one-line summary
   * @example "Card payments failing"
   */
  shortDescription?: string
  /**
   * As the tool reports it, e.g. In Progress or Resolved
   * @example "In Progress"
   */
  state?: string
  /**
   * The priority the tool gives it, as written there
   * @example "1 - Critical"
   */
  priority?: string
  /**
   * The severity the tool gives it, as written there
   * @example "1 - High"
   */
  severity?: string
  /**
   * When the incident was opened
   * @example "2026-09-30T12:05:00Z"
   */
  openedAt?: string
  /**
   * When the incident was resolved, if it has been
   * @example "2026-09-30T13:40:00Z"
   */
  resolvedAt?: string
  /**
   * How the incident was closed, as the tool reports it
   * @example "Solved (Permanently)"
   */
  closeCode?: string
  /**
   * The group assigned to resolve the incident
   * @example "Payments Support"
   */
  assignmentGroup?: string
}

export type LibraryEcosystem = 'npm' | 'maven' | 'pypi' | 'go'

/** A third-party package a repository depends on, as its ecosystem names it. */
export interface Library {
  id: string
  /**
   * The package ecosystem; Gradle dependencies are maven
   * @example "npm"
   */
  ecosystem: LibraryEcosystem
  /**
   * The package name as the manifest spells it
   * @example "vue"
   */
  name: string
  /**
   * What the package does, in a sentence
   * @example "The progressive JavaScript framework"
   */
  description?: string
}

export type IacFileFormat = 'terraform' | 'cdk' | 'bicep' | 'cloudformation'

/** An infrastructure-as-code file, and the resources it names. */
export interface IacFile {
  id: string
  /**
   * Key of the repository holding the file
   * @example "github.com/acme/payments"
   */
  repoKey: string
  /**
   * Path within the default branch
   * @example "infra/db.tf"
   */
  path: string
  /**
   * The infrastructure-as-code language of the file
   * @example "terraform"
   */
  format: IacFileFormat
  /**
   * Identifiers the file names literally, for the link engine to match on
   * @example ["arn:aws:rds:eu-west-1:123456789012:db:payments"]
   */
  resourceRefs?: string[]
}

/** A commit range or merge in a repository, identified there by its head sha. */
export interface Change {
  id: string
  /**
   * Key of the repository the change was made in, e.g. github.com/acme/payments
   * @example "github.com/acme/payments"
   */
  repositoryKey: string
  /**
   * The commit the change ends at, stored in lower case
   * @example "9fceb02d0ae598e95dc970b74767f19372d61af8"
   */
  sha: string
  /**
   * The commit the change starts from, for a range
   * @example "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
   */
  baseSha?: string
  /**
   * The change's summary, such as its commit subject
   * @example "Retry the payment webhook"
   */
  title?: string
  /**
   * Who made the change, as the forge names them
   * @example "octocat"
   */
  author?: string
  /**
   * When the change was committed
   * @example "2026-09-30T10:00:00Z"
   */
  committedAt: string
  /**
   * Where the change can be read, e.g. the commit or compare page
   * @example "https://github.com/acme/payments/commit/9fceb02d0ae598e95dc970b74767f19372d61af8"
   */
  url?: string
}

export type PullRequestState = 'open' | 'merged' | 'closed'

/** A proposal to merge a change into a repository, as the forge records it. */
export interface PullRequest {
  id: string
  /**
   * Key of the repository the pull request targets
   * @example "github.com/acme/payments"
   */
  repositoryKey: string
  /**
   * The forge's number for it within the repository
   * @example 42
   */
  number: number
  /**
   * Where the pull request can be read on the forge
   * @example "https://github.com/acme/payments/pull/42"
   */
  url: string
  /**
   * The pull request's title
   * @example "Retry the payment webhook"
   */
  title?: string
  /**
   * Whether it is open, merged or closed unmerged
   * @example "merged"
   */
  state?: PullRequestState
  /**
   * When it was merged, if it has been
   * @example "2026-09-30T11:00:00Z"
   */
  mergedAt?: string
  /**
   * The head branch, e.g. chorus/CH-42-retry-webhook
   * @example "chorus/CH-42-retry-webhook"
   */
  branch?: string
}

export type ExternalWorkItemSystem = 'chorus' | 'jira' | 'linear' | 'github' | 'other'

/** A task, ticket or issue in the system that owns it, referred to by its URI. */
export interface ExternalWorkItem {
  id: string
  /**
   * e.g. chorus://task/01J... or https://acme.atlassian.net/browse/PAY-42
   * @example "https://acme.atlassian.net/browse/PAY-42"
   */
  uri: string
  /**
   * The system that owns the work item
   * @example "jira"
   */
  system: ExternalWorkItemSystem
  /**
   * Its human-facing identifier there, e.g. CH-42
   * @example "PAY-42"
   */
  externalKey?: string
  /**
   * The work item's title in the owning system
   * @example "Retry the payment webhook"
   */
  title?: string
}

/** Records which ontology version the graph was built with. */
export interface Ontology {
  id: string
  /**
   * The ontology version, as version.yaml declares it
   * @example "1.4.0"
   */
  version: string
  /**
   * When a build first recorded this version
   * @example "2026-09-30T12:00:00Z"
   */
  loadedAt?: string
}

export type SyncRunMode = 'FULL' | 'INCREMENTAL' | 'WEBHOOK'

export type SyncRunStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'

/** One execution of a connector, for auditing what a sync changed. */
export interface SyncRun {
  id: string
  /**
   * The connector that ran, by its registered name
   * @example "github"
   */
  connector: string
  /**
   * What this run stamped on the provenance of everything it wrote
   * @example "github"
   */
  sourceSystem?: string
  /**
   * Whether it read everything, what changed, or one delivery
   * @example "INCREMENTAL"
   */
  mode?: SyncRunMode
  /**
   * The source system's id for the delivery that caused this run
   * @example "72d3162e-cc78-11e3-81ab-4c9367dc0958"
   */
  sourceId?: string
  /**
   * When the run started
   * @example "2026-09-30T12:00:00Z"
   */
  startedAt?: string
  /**
   * When the run finished, if it has
   * @example "2026-09-30T12:01:30Z"
   */
  finishedAt?: string
  /**
   * Whether the run is going, worked, partly worked or failed
   * @example "SUCCESS"
   */
  status?: SyncRunStatus
  /**
   * Nodes the run created or updated
   * @example 42
   */
  nodesUpserted?: number
  /**
   * Edges the run created or updated
   * @example 57
   */
  edgesUpserted?: number
  /**
   * Facts this run closed because the source stopped reporting them
   * @example 0
   */
  tombstones?: number
  /**
   * Nodes and edges the run created or changed
   * @example 12
   */
  written?: number
  /**
   * Nodes and edges the run stated again exactly as the graph held them
   * @example 87
   */
  unchanged?: number
  /**
   * Items the run could not read or write: each one its connector named, each page
   * @example 1
   */
  failed?: number
  /**
   * Version of the connector that ran, as its descriptor declares it
   * @example "2.0.0"
   */
  connectorVersion?: string
  /**
   * Where the next incremental run should start
   * @example "2026-09-30T12:00:00Z"
   */
  watermark?: string
  /**
   * Why the run failed or partly failed, where it did
   * @example "GitHub returned 502 for acme/payments"
   */
  error?: string
}

export type ConnectorStateLastStatus = 'SUCCESS' | 'PARTIAL' | 'FAILED'

export type ConnectorStateLastRunStatus = 'SUCCESS' | 'PARTIAL' | 'FAILED'

/** What the graph remembers about a connector between runs. */
export interface ConnectorState {
  id: string
  /**
   * The connector this state belongs to
   * @example "github"
   */
  connector: string
  /**
   * Reported by the last successful run
   * @example "2026-09-30T12:00:00Z"
   */
  watermark?: string
  /**
   * The last scheduled or manual run to finish, whatever its status
   * @example "0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b"
   */
  lastRunId?: string
  /**
   * That run's status; the same as lastRunStatus, kept for states written before it
   * @example "SUCCESS"
   */
  lastStatus?: ConnectorStateLastStatus
  /**
   * How the last run to finish ended
   * @example "SUCCESS"
   */
  lastRunStatus?: ConnectorStateLastRunStatus
  /**
   * When the last run to finish finished
   * @example "2026-09-30T12:01:30Z"
   */
  lastFinishedAt?: string
  /**
   * When the last successful run finished
   * @example "2026-09-30T12:01:30Z"
   */
  lastSuccessAt?: string
  /**
   * Runs that ended PARTIAL or FAILED since the last SUCCESS
   * @example 0
   */
  consecutiveFailures?: number
}

export type NodeVersionRetiredReason =
  'source-deleted' | 'source-retired' | 'missing-from-sync' | 'manual' | 'merged'

/** The values a node held for an interval before they were replaced. */
export interface NodeVersion {
  id: string
  /**
   * The id of the node these values belonged to
   * @example "Repository:github.com/acme/payments"
   */
  versionOf: string
  /**
   * When the node began to hold these values
   * @example "2026-01-01T00:00:00Z"
   */
  since: string
  /**
   * When a write replaced them, or the node was retired
   * @example "2026-02-01T00:00:00Z"
   */
  until: string
  /**
   * True when the values ended because the node was retired rather than changed
   * @example false
   */
  retired?: boolean
  /**
   * Why the node was retired, where it was
   * @example "missing-from-sync"
   */
  retiredReason?: NodeVersionRetiredReason
}

/** A versioned migration of the graph's data, and when it was applied. */
export interface OntologyMigration {
  id: string
  /**
   * The ontology version the migration brings the graph to
   * @example "1.4.0"
   */
  version: string
  /**
   * The migration's name, from its file name
   * @example "rename_ci_legacy_name"
   */
  name: string
  /**
   * SHA-256 of the file that was applied
   * @example "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
   */
  checksum: string
  /**
   * When it was applied or recorded
   * @example "2026-09-30T12:00:00Z"
   */
  appliedAt: string
  /**
   * How long it took to run, in milliseconds
   * @example 12
   */
  durationMs?: number
  /**
   * True when recorded without running, because the graph never needed it
   * @example false
   */
  baseline?: boolean
}

/** A connector or agent registered as a principal of its own, owned by a team. */
export interface ServicePrincipal {
  id: string
  /**
   * The client id its tokens carry (azp, or client_id)
   * @example "payments-deployer"
   */
  name: string
  /**
   * Key of the Team that answers for it; recorded on its writes as onBehalfOfTeam
   * @example "platform"
   */
  ownedBy: string
  /**
   * What the principal is for, in a sentence
   * @example "Reports deployments of the payments service"
   */
  description?: string
}

export type NodeType =
  | 'Repository'
  | 'Team'
  | 'Service'
  | 'Pipeline'
  | 'Artifact'
  | 'Deployment'
  | 'Environment'
  | 'CloudResource'
  | 'ConfigurationItem'
  | 'ChangeRequest'
  | 'Incident'
  | 'Library'
  | 'IacFile'
  | 'Change'
  | 'PullRequest'
  | 'ExternalWorkItem'
  | 'Ontology'
  | 'SyncRun'
  | 'ConnectorState'
  | 'NodeVersion'
  | 'OntologyMigration'
  | 'ServicePrincipal'

export const NODE_TYPES: readonly NodeType[] = [
  'Repository',
  'Team',
  'Service',
  'Pipeline',
  'Artifact',
  'Deployment',
  'Environment',
  'CloudResource',
  'ConfigurationItem',
  'ChangeRequest',
  'Incident',
  'Library',
  'IacFile',
  'Change',
  'PullRequest',
  'ExternalWorkItem',
  'Ontology',
  'SyncRun',
  'ConnectorState',
  'NodeVersion',
  'OntologyMigration',
  'ServicePrincipal'
]

export type EdgeTypeName =
  | 'OWNED_BY'
  | 'OWNS_RESOURCE'
  | 'DEPENDS_ON'
  | 'CONTAINS_IAC'
  | 'HAS_PIPELINE'
  | 'RELATES_TO_CI'
  | 'AFFECTS'
  | 'CAUSED_BY'
  | 'BUILT_FROM'
  | 'DEPLOYED_TO'
  | 'TO_ENVIRONMENT'
  | 'PROVIDES'
  | 'INTRODUCED_IN'
  | 'MERGES'
  | 'CONTAINS'
  | 'IMPLEMENTS'
  | 'TRACKED_IN'
  | 'PRODUCED'

export const EDGE_TYPES: readonly EdgeTypeName[] = [
  'OWNED_BY',
  'OWNS_RESOURCE',
  'DEPENDS_ON',
  'CONTAINS_IAC',
  'HAS_PIPELINE',
  'RELATES_TO_CI',
  'AFFECTS',
  'CAUSED_BY',
  'BUILT_FROM',
  'DEPLOYED_TO',
  'TO_ENVIRONMENT',
  'PROVIDES',
  'INTRODUCED_IN',
  'MERGES',
  'CONTAINS',
  'IMPLEMENTS',
  'TRACKED_IN',
  'PRODUCED'
]

export type ContextPackTemplate = 'change-impact' | 'incident-triage' | 'data-consumers'

export const CONTEXT_PACK_TEMPLATES: readonly ContextPackTemplate[] = [
  'change-impact',
  'incident-triage',
  'data-consumers'
]
