import { apiClient as client } from './api'

/**
 * How far one source system is behind its freshness window (#93, FR-3). `lastSuccessAt` and
 * `lagSeconds` are absent for a source that has never synced successfully.
 */
export interface SourceLagView {
  source: string
  window: string
  windowSeconds: number
  lastSuccessAt?: string | null
  lagSeconds?: number | null
  lagging: boolean
}

/**
 * The lag of each source, as `GET /api/v1/freshness` reports it. The `freshness` health component
 * says the same, but the web interface proxies no health details, so it reads this instead.
 */
export const freshnessApi = {
  sources: (): Promise<SourceLagView[]> =>
    client.get<{ sources: SourceLagView[] }>('/freshness').then(r => r.data.sources)
}
