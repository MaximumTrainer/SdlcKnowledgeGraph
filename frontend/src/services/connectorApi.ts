import { apiClient as client } from './api'

/** What a connector is allowed to be asked to do. */
export type Capability = 'FULL' | 'INCREMENTAL' | 'WEBHOOK' | 'DISCOVERY'

export interface HealthView {
  status: 'UP' | 'DOWN'
  detail: string | null
}

export interface LastRunView {
  id: string | null
  status: string | null
  finishedAt: string | null
  watermark: string | null
}

export interface ConnectorSummary {
  name: string
  sourceSystem: string
  enabled: boolean
  capabilities: Capability[]
  health: HealthView
  lastRun: LastRunView | null
}

export interface SyncRun {
  id: string
  connector: string
  mode: string | null
  status: string | null
  startedAt: string | null
  finishedAt: string | null
  nodesUpserted: number
  edgesUpserted: number
  tombstones: number
  watermark: string | null
  error: string | null
}

/**
 * Ingestion, as the connectors screen needs it.
 *
 * A sync returns a run id rather than a result: reading somebody else's estate takes as long as it
 * takes, so the API answers 202 and the caller follows the run (#22).
 */
export const connectorApi = {
  list: (): Promise<ConnectorSummary[]> => client.get('/connectors').then(r => r.data),
  get: (name: string): Promise<ConnectorSummary> =>
    client.get(`/connectors/${name}`).then(r => r.data),
  runs: (name: string, limit = 20): Promise<SyncRun[]> =>
    client.get(`/connectors/${name}/runs`, { params: { limit } }).then(r => r.data),
  sync: (
    name: string,
    mode: 'full' | 'incremental' = 'incremental'
  ): Promise<{ syncRunId: string }> =>
    client.post(`/connectors/${name}/sync`, null, { params: { mode } }).then(r => r.data)
}
