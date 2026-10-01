import { apiClient as client } from './api'

/**
 * Where a candidate link stands (#28). Pending and conflict are open for review; rejected and
 * superseded are decisions the engine keeps to while the evidence is the same.
 */
export type CandidateStatus = 'pending' | 'conflict' | 'rejected' | 'superseded' | 'accepted'

export const REVIEWABLE_STATUSES: CandidateStatus[] = [
  'pending',
  'conflict',
  'rejected',
  'superseded'
]

export interface LinkedResource {
  key: string
  name: string | null
  provider: string | null
  accountId: string | null
}

export interface LinkedRepository {
  key: string
  name: string | null
}

/** A repository a rule proposed as a resource's owner, below the threshold or against the owner. */
export interface CandidateLink {
  id: string
  resource: LinkedResource
  repository: LinkedRepository
  confidence: number
  /** The rule whose evidence is strongest: tag, deployment, iac or naming. */
  rule: string
  evidence: Record<string, string>
  status: CandidateStatus
  createdAt: string | null
  rejectedBy?: string | null
  rejectedAt?: string | null
}

export interface CandidatePage {
  items: CandidateLink[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** Every filter is optional; with no status the API lists the open candidates, pending and conflict. */
export interface CandidateQuery {
  status?: string
  provider?: string
  minConfidence?: string | number
  q?: string
  page?: number
  size?: number
}

/** An OWNS_RESOURCE as the link endpoints answer it. */
export interface OwnerLink {
  resource: LinkedResource
  repository: LinkedRepository
  rule: string
  confidence: number
  inferred: boolean
  evidence: Record<string, string>
  acceptedBy: string | null
  sourceSystem: string
}

export interface ResolutionStarted {
  syncRunId: string
  mode: 'FULL' | 'INCREMENTAL'
}

/**
 * The link engine's review (#28): its candidates, a person's decision on one, and a resolution
 * started on demand. Empty filters are left out rather than sent blank, which the API would refuse.
 */
export const linkApi = {
  candidates: (query: CandidateQuery = {}): Promise<CandidatePage> =>
    client
      .get('/links/candidates', {
        params: Object.fromEntries(
          Object.entries(query).filter(([, value]) => value !== undefined && value !== '')
        )
      })
      .then(r => r.data),
  accept: (id: string): Promise<OwnerLink> =>
    client.post(`/links/candidates/${encodeURIComponent(id)}/accept`).then(r => r.data),
  reject: (id: string): Promise<CandidateLink> =>
    client.post(`/links/candidates/${encodeURIComponent(id)}/reject`).then(r => r.data),
  resolve: (): Promise<ResolutionStarted> => client.post('/links/resolve', {}).then(r => r.data)
}
