/**
 * What the signed-in user may do to the graph, read from the graph scopes on their access token
 * (#116): graph:read to read it, graph:write to change it.
 *
 * The web interface uses this only to decide what to offer. The API checks the same token on every
 * request, so reading it here needs no signature check: a token read wrongly costs a refused request,
 * never a write that should not have happened.
 */
export const GRAPH_WRITE = 'graph:write'

const PREFIX = 'graph:'

const decodePayload = (token: string): Record<string, unknown> | null => {
  const payload = token.split('.')[1]
  if (!payload) return null
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(base64), char => char.charCodeAt(0))
    const claims: unknown = JSON.parse(new TextDecoder().decode(bytes))
    return claims && typeof claims === 'object' ? (claims as Record<string, unknown>) : null
  } catch {
    return null
  }
}

const values = (claim: unknown): string[] => {
  if (typeof claim === 'string') return claim.split(/\s+/).filter(Boolean)
  if (Array.isArray(claim)) return claim.flatMap(values)
  return []
}

/**
 * The graph scopes an access token holds, sorted: from `scope` (a space-separated string, as
 * Keycloak issues it) and `scp` (an array, as some issuers do). Empty for anything that is not a JWT.
 */
export const graphScopesOf = (token: string | null | undefined): string[] => {
  const claims = token ? decodePayload(token) : null
  if (!claims) return []
  const scopes = [...values(claims.scope), ...values(claims.scp)].filter(
    scope => scope.startsWith(PREFIX) && scope.length > PREFIX.length
  )
  return [...new Set(scopes)].sort()
}

interface InsufficientScope {
  error: 'insufficient scope'
  required: string[]
  held: string[]
}

const isInsufficientScope = (data: unknown): data is InsufficientScope => {
  const refusal = data as Partial<InsufficientScope> | null | undefined
  return (
    refusal?.error === 'insufficient scope' &&
    Array.isArray(refusal.required) &&
    Array.isArray(refusal.held)
  )
}

/**
 * The sentence to show when the API refused a request for want of a scope, or null for any other
 * refusal, which the page that got it explains in its own words.
 */
export const refusalReason = (data: unknown): string | null => {
  if (!isInsufficientScope(data)) return null
  const held = data.held.length ? data.held.join(', ') : 'no graph scope'
  return `You do not have permission to do that: it needs ${data.required.join(', ')}, and you hold ${held}.`
}
