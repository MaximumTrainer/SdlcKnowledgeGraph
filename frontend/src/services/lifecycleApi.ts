import { apiClient as client } from './api'

/** A migration the graph has not had yet. */
export interface PendingMigration {
  version: string
  name: string
  checksum: string
  description: string | null
}

/** A migration the graph has had; a baseline was recorded without running, as the graph never needed it. */
export interface AppliedMigration {
  version: string
  name: string
  checksum: string
  appliedAt: string
  durationMs: number
  baseline: boolean
}

/** Which ontology version the graph is on beside the build's, and what lies between (#33). */
export interface MigrationStatus {
  registryVersion: string
  dbVersion: string | null
  mode: 'auto' | 'manual' | string
  upToDate: boolean
  pending: PendingMigration[]
  applied: AppliedMigration[]
}

export interface ArchiveCounts {
  nodes: number
  edges: number
}

export interface ArchiveStatus {
  enabled: boolean
  mode: 'dry-run' | 'export' | 'purge' | string
  /** ISO-8601, whole days as P365D. */
  retention: string
  schedule: string
  cutoff: string
  eligible: ArchiveCounts
}

export interface ConnectorRules {
  name: string
  sourceSystem: string
  missingFromFullSync: 'tombstone' | 'ignore' | string
  gracePeriod: string
  requireSuccessfulRun: boolean
  fullSyncIsComplete: boolean
}

export interface LifecycleStatus {
  registryVersion: string
  migrations: MigrationStatus
  versioning: { enabled: boolean; maxVersions: number; excludedTypes: string[] }
  archive: ArchiveStatus
  connectors: ConnectorRules[]
}

export interface MigrationApplyResult {
  applied: AppliedMigration[]
  dbVersion: string | null
}

export interface ArchiveResult {
  dryRun: boolean
  mode: string
  cutoff: string
  wouldArchive?: ArchiveCounts
  archived?: ArchiveCounts
  purged?: ArchiveCounts
  file?: string
  syncRunId?: string
}

/** What a node holds now: since when it has held these values, and whether it has been retired. */
export interface CurrentValidity {
  validFrom: string
  validTo: string | null
  propsFrom: string
  retired: boolean
  retiredReason: string | null
  resurrectedAt: string | null
  props: Record<string, unknown>
}

/** Values a node held before a write replaced them, or before it was retired. */
export interface NodeVersion {
  validFrom: string
  validTo: string
  retired: boolean
  retiredReason: string | null
  props: Record<string, unknown>
  provenance: { sourceSystem: string } & Record<string, unknown>
}

export interface NodeHistory {
  nodeId: string
  current: CurrentValidity
  versions: NodeVersion[]
}

/**
 * The data lifecycle (#33): the administration page's status, applying the ontology migrations,
 * rehearsing or running the archive, and a node's history.
 */
export const lifecycleApi = {
  status: (): Promise<LifecycleStatus> => client.get('/lifecycle').then(r => r.data),
  migrations: (): Promise<MigrationStatus> => client.get('/lifecycle/migrations').then(r => r.data),
  applyMigrations: (): Promise<MigrationApplyResult> =>
    client.post('/lifecycle/migrations/apply').then(r => r.data),
  archive: ({ dryRun }: { dryRun: boolean }): Promise<ArchiveResult> =>
    client.post('/lifecycle/archive', null, { params: { dryRun } }).then(r => r.data),
  history: (nodeId: string): Promise<NodeHistory> =>
    client.get('/lifecycle/history', { params: { nodeId } }).then(r => r.data)
}

/** A duration as a person reads it: P7D as "7 days", P0D as "none", anything else as given. */
export const describeDuration = (iso: string): string => {
  const days = /^P(\d+)D$/.exec(iso)
  if (!days) return iso
  const count = Number(days[1])
  if (count === 0) return 'none'
  return count === 1 ? '1 day' : `${count} days`
}
