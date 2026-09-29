import { apiClient as client } from './api'

/** A run's outcome. RUNNING until it finishes; PARTIAL when some pages failed and the rest held. */
export type RunStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'

export const RUN_STATUSES: RunStatus[] = ['RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED']

/** One run as the history lists it. `error` is cut to 200 characters; the run in full has it all. */
export interface SyncRunSummary {
  id: string
  connector: string
  sourceSystem: string | null
  mode: string | null
  status: string | null
  startedAt: string | null
  finishedAt: string | null
  /** Null while the run is still going. */
  durationMs: number | null
  nodesUpserted: number
  edgesUpserted: number
  tombstones: number
  error: string | null
}

/** One run in full: the whole error, where it got to, and what the connector said beyond counts. */
export interface SyncRunDetail extends SyncRunSummary {
  watermark: string | null
  sourceId: string | null
  details: Record<string, unknown>
}

export interface SyncRunPage {
  items: SyncRunSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Every filter is optional; `from` and `to` are ISO-8601 instants, the window half-open. */
export interface SyncRunQuery {
  connector?: string
  status?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

/**
 * The history of connector runs, across every connector, newest first (#29, FR5).
 *
 * Empty filters are left out rather than sent blank: the API reads `status=` as a status it does not
 * know and refuses it.
 */
export const syncRunApi = {
  list: (query: SyncRunQuery = {}): Promise<SyncRunPage> =>
    client
      .get('/sync-runs', {
        params: Object.fromEntries(
          Object.entries(query).filter(([, value]) => value !== undefined && value !== '')
        )
      })
      .then(r => r.data),
  get: (id: string): Promise<SyncRunDetail> =>
    client.get(`/sync-runs/${encodeURIComponent(id)}`).then(r => r.data)
}
