import axios from 'axios'

/** The identity provider the deployment signs users in with (#114). */
export interface AuthConfig {
  /** The OIDC issuer, e.g. http://localhost:8081/realms/sdlc. */
  authority: string
  /** The public client registered for this web interface. */
  clientId: string
}

/**
 * Reads the deployment's login settings from /auth-config.json, which nginx renders from its
 * environment (OIDC_AUTHORITY, OIDC_CLIENT_ID). Read at runtime rather than baked in at build time,
 * so the same image serves a stack with an identity provider and one without.
 *
 * No authority, or no file at all (the Vite dev server serves none), means the deployment has no
 * login: the API is running its anonymous read-only mode (#118), and the web interface reads without
 * signing anyone in.
 */
export const loadAuthConfig = async (): Promise<AuthConfig | null> => {
  try {
    const { data } = await axios.get<Partial<AuthConfig>>('/auth-config.json', {
      headers: { Accept: 'application/json' }
    })
    if (typeof data?.authority !== 'string' || data.authority.trim() === '') return null
    return { authority: data.authority, clientId: data.clientId || 'sdlc-ui' }
  } catch {
    return null
  }
}
