// GENERATED FROM ontology/v1 - DO NOT EDIT
// Run ./gradlew generateOntology after changing the registry.

export const ONTOLOGY_VERSION = '1.0.0'

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
}

/** A git repository, the anchor for most of the graph. */
export interface Repository {
  id: string
  url: string
  host?: string
  org?: string
  name?: string
  defaultBranch: string
  topics: string[]
  codeowners: string[]
  serviceId?: string
  language?: string
  description?: string
}

/** A group that owns repositories, services or infrastructure. */
export interface Team {
  id: string
  name: string
  email?: string
}

/** A running logical component, which may span more than one repository. */
export interface Service {
  id: string
  name: string
  description?: string
  tier?: string
}

/** A CI/CD workflow definition. */
export interface Pipeline {
  id: string
  provider: string
  repoKey?: string
  workflowPath?: string
  name: string
  repoId: string
  lastRunStatus?: string
}

/** A built, addressable output such as a container image. */
export interface Artifact {
  id: string
  registry?: string
  name: string
  digest?: string
  version: string
  commitSha?: string
  repoId?: string
  artifactType: string
}

/** One event of putting an artifact into an environment. */
export interface Deployment {
  id: string
  artifactKey?: string
  environmentKey?: string
  deployedAt: string
  artifactId: string
  environmentId: string
  deployedBy?: string
  status: string
}

/** A deployment target such as staging or production. */
export interface Environment {
  id: string
  name: string
  type: string
}

/** An infrastructure object in AWS, Azure or GCP. */
export interface CloudResource {
  id: string
  provider: string
  resourceId?: string
  resourceType: string
  name: string
  region?: string
  accountId?: string
  repoId?: string
}

/** A configuration item from a service management system such as ServiceNow. */
export interface ConfigurationItem {
  id: string
  sourceSystem: string
  instance: string
  sysId: string
  ciName: string
  ciClass?: string
  serviceId?: string
}

/** Records which ontology version the graph was built with. */
export interface Ontology {
  id: string
  version: string
  loadedAt?: string
}

/** One execution of a connector, for auditing what a sync changed. */
export interface SyncRun {
  id: string
  connector: string
  sourceSystem?: string
  mode?: string
  startedAt?: string
  finishedAt?: string
  status?: string
  nodesUpserted?: number
  edgesUpserted?: number
  tombstones?: number
  watermark?: string
  error?: string
}

/** What the graph remembers about a connector between runs. */
export interface ConnectorState {
  id: string
  connector: string
  watermark?: string
  lastRunId?: string
  lastStatus?: string
  lastFinishedAt?: string
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
  | 'Ontology'
  | 'SyncRun'
  | 'ConnectorState'

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
  'Ontology',
  'SyncRun',
  'ConnectorState'
]

export type EdgeTypeName =
  | 'OWNED_BY'
  | 'OWNS_RESOURCE'
  | 'DEPENDS_ON'
  | 'HAS_PIPELINE'
  | 'RELATES_TO_CI'
  | 'BUILT_FROM'
  | 'DEPLOYED_TO'
  | 'TO_ENVIRONMENT'
  | 'PROVIDES'
  | 'PRODUCED'

export const EDGE_TYPES: readonly EdgeTypeName[] = [
  'OWNED_BY',
  'OWNS_RESOURCE',
  'DEPENDS_ON',
  'HAS_PIPELINE',
  'RELATES_TO_CI',
  'BUILT_FROM',
  'DEPLOYED_TO',
  'TO_ENVIRONMENT',
  'PROVIDES',
  'PRODUCED'
]
