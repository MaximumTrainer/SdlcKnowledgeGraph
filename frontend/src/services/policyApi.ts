import { ref, type Ref } from 'vue'
import type { AxiosError, AxiosInstance } from 'axios'
import { apiClient as client } from './api'

/**
 * The authorisation policy in force (#30, ADR-0020): its revision, what evaluates it, and what
 * happens when it cannot be evaluated - `closed`, a request is refused rather than answered unchecked.
 */
export interface PolicyStatus {
  name: string
  revision: string
  loadedAt: string
  engine: string
  status: string
  failMode: string
}

/** The actions the policy decides, as the API names them. */
export const POLICY_ACTIONS = [
  'read',
  'query',
  'create',
  'update',
  'delete',
  'link',
  'sync',
  'admin'
] as const
export type PolicyAction = (typeof POLICY_ACTIONS)[number]

/** The caller as the policy sees them. `roles` is null for a token that carries no roles claim. */
export interface PolicySubject {
  id: string
  kind: 'user' | 'service' | 'agent' | 'anonymous' | string
  scopes: string[]
  roles: string[] | null
  teams: string[]
}

/** What the policy decides for the caller, by which rule, and why. */
export interface PolicyExplanation {
  allow: boolean
  policy: string
  reason: string
  required: string[]
  redact: string[]
  clearance: string
  subject: PolicySubject
}

/** The body of a 403 the policy gave for a reason other than a missing scope (#95 FR-1). */
export interface PolicyDenial {
  error: 'policy denied'
  policy: string
  reason: string
}

export const isPolicyDenial = (data: unknown): data is PolicyDenial => {
  const denial = data as Partial<PolicyDenial> | null | undefined
  return (
    denial?.error === 'policy denied' &&
    typeof denial.policy === 'string' &&
    typeof denial.reason === 'string'
  )
}

export const policyApi = {
  status: (): Promise<PolicyStatus> => client.get<PolicyStatus>('/policy').then(r => r.data),

  /** What the policy decides for the signed-in user doing [action] to a node type, or one node. */
  explain: (
    action: PolicyAction,
    resource: { type?: string; key?: string } = {}
  ): Promise<PolicyExplanation> =>
    client.post<PolicyExplanation>('/policy/explain', { action, resource }).then(r => r.data)
}

/**
 * The last request the policy refused, for the banner that says so (AccessDeniedBanner). A refusal
 * for want of a scope is left to the page that got it, which says what to ask for.
 */
export const lastPolicyDenial: Ref<PolicyDenial | null> = ref(null)

/** Records every `403 policy denied` [http] receives in [lastPolicyDenial], then fails as before. */
export const watchPolicyDenials = (http: AxiosInstance = client): void => {
  http.interceptors.response.use(
    response => response,
    (error: AxiosError) => {
      const data = error.response?.data
      if (error.response?.status === 403 && isPolicyDenial(data)) lastPolicyDenial.value = data
      return Promise.reject(error)
    }
  )
}
