import { ref, type InjectionKey, type Ref } from 'vue'
import {
  UserManager,
  WebStorageStateStore,
  type SigninRedirectArgs,
  type User,
  type UserManagerSettings
} from 'oidc-client-ts'
import type { AuthConfig } from './config'
import { graphScopesOf } from './scopes'

/** Where the identity provider sends the browser back to after sign-in. */
export const CALLBACK_PATH = '/auth/callback'

/**
 * The signed-in user, as the rest of the web interface sees it (#114).
 *
 * oidc-client-ts does the protocol: the authorization code flow with PKCE against a public client, so
 * the browser holds no secret, and silent renewal with the refresh token, so an expiring access token
 * is replaced without the user noticing.
 */
export interface AuthSession {
  /** Who is signed in, for display. Null until a session loads. */
  readonly username: Ref<string | null>
  /** The graph scopes the signed-in user's access token holds (#116). Empty until a session loads. */
  readonly scopes: Ref<string[]>
  /** A current access token, or null when there is none and the user has to sign in. */
  accessToken(): Promise<string | null>
  /** Hands over to the identity provider, coming back to [returnTo] afterwards. */
  signIn(returnTo: string): Promise<void>
  /** Finishes a sign-in on the callback route and says where the user was going. */
  completeSignIn(url?: string): Promise<string>
  signOut(): Promise<void>
}

/** The part of oidc-client-ts's UserManager this module uses, so tests can stand in for it. */
export interface SignInManager {
  getUser(): Promise<User | null>
  signinRedirect(args?: SigninRedirectArgs): Promise<void>
  signinRedirectCallback(url?: string): Promise<User>
  signoutRedirect(): Promise<void>
  events: {
    addUserLoaded(callback: (user: User) => void): unknown
    addUserUnloaded(callback: () => void): unknown
  }
}

export const AUTH_SESSION: InjectionKey<AuthSession | null> = Symbol('auth-session')

/**
 * The UserManager settings for [config]. The session lives in sessionStorage: it ends with the tab,
 * and is not shared with other tabs the way localStorage would share it.
 */
export const settings = (
  config: AuthConfig,
  origin: string = window.location.origin
): UserManagerSettings => ({
  authority: config.authority,
  client_id: config.clientId,
  redirect_uri: `${origin}${CALLBACK_PATH}`,
  post_logout_redirect_uri: `${origin}/`,
  response_type: 'code',
  scope: 'openid profile',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage })
})

const displayName = (user: User | null): string | null =>
  user ? String(user.profile.preferred_username ?? user.profile.sub) : null

const returnTo = (state: unknown): string => {
  const target = (state as { returnTo?: unknown } | undefined)?.returnTo
  // Only a path on this site: a crafted state must not turn sign-in into an open redirect.
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    ? target
    : '/'
}

export const createAuthSession = (
  config: AuthConfig,
  manager: SignInManager = new UserManager(settings(config))
): AuthSession => {
  const username = ref<string | null>(null)
  const scopes = ref<string[]>([])
  const remember = (user: User | null) => {
    username.value = displayName(user)
    scopes.value = graphScopesOf(user?.access_token)
  }
  manager.events.addUserLoaded(remember)
  manager.events.addUserUnloaded(() => remember(null))

  return {
    username,
    scopes,
    async accessToken() {
      const user = await manager.getUser()
      if (!user || user.expired) return null
      remember(user)
      return user.access_token
    },
    async signIn(target: string) {
      await manager.signinRedirect({ state: { returnTo: target } })
    },
    async completeSignIn(url?: string) {
      const user = await manager.signinRedirectCallback(url)
      remember(user)
      return returnTo(user.state)
    },
    async signOut() {
      await manager.signoutRedirect()
    }
  }
}
