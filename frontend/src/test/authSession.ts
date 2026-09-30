import { ref } from 'vue'
import { AUTH_SESSION, type AuthSession } from '@/auth/session'

/** base64url, as a JWT encodes its parts. */
const encode = (value: unknown): string =>
  btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')

/** An access token carrying [claims]. Unsigned: the web interface only reads it, the API verifies. */
export const accessTokenWith = (claims: Record<string, unknown>): string =>
  `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode(claims)}.signature`

/** A signed-in session holding [scopes], for mounting a view as a particular kind of user (#116). */
export const sessionWith = (scopes: string[], username = 'reader'): AuthSession => ({
  username: ref(username),
  scopes: ref(scopes),
  accessToken: async () => accessTokenWith({ sub: username, scope: scopes.join(' ') }),
  signIn: async () => undefined,
  completeSignIn: async () => '/',
  signOut: async () => undefined
})

/**
 * What `mount`'s `global.provide` needs to hand a view [session]; null for a deployment with no login,
 * the anonymous read-only mode (#118).
 */
export const providing = (session: AuthSession | null) => ({ [AUTH_SESSION as symbol]: session })

export const READ_ONLY = ['graph:read']
export const READ_WRITE = ['graph:read', 'graph:write']

/** The API's refusal of a write from a read-only token (#116). */
export const insufficientScope = {
  error: 'insufficient scope',
  required: ['graph:write'],
  held: ['graph:read']
}
