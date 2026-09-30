import { describe, expect, it, vi } from 'vitest'
import type { User } from 'oidc-client-ts'
import { CALLBACK_PATH, createAuthSession, settings, type SignInManager } from './session'
import { accessTokenWith } from '@/test/authSession'

/**
 * The session is a thin layer over oidc-client-ts's UserManager, which does the protocol: the
 * authorization code flow with PKCE, and silent renewal with the refresh token. These tests pin what
 * the application relies on it for.
 */
const user = (overrides: Partial<User> = {}) =>
  ({
    access_token: 'access-token',
    expired: false,
    profile: { sub: 'dan', preferred_username: 'dan' },
    state: undefined,
    ...overrides
  }) as unknown as User

const fakeManager = (current: User | null = null) => {
  const loaded: ((u: User) => void)[] = []
  const manager: SignInManager = {
    getUser: vi.fn(async () => current),
    signinRedirect: vi.fn(async () => undefined),
    signinRedirectCallback: vi.fn(async () => user({ state: { returnTo: '/nodes/Team/new' } })),
    signoutRedirect: vi.fn(async () => undefined),
    events: {
      addUserLoaded: (cb: (u: User) => void) => loaded.push(cb),
      addUserUnloaded: () => undefined
    }
  }
  return { manager, loaded }
}

const config = { authority: 'http://localhost:8081/realms/sdlc', clientId: 'sdlc-ui' }

describe('settings', () => {
  it('asks for the code flow, returns to the callback route and renews silently', () => {
    const s = settings(config, 'http://localhost:5173')

    expect(s.authority).toBe('http://localhost:8081/realms/sdlc')
    expect(s.client_id).toBe('sdlc-ui')
    expect(s.response_type).toBe('code')
    expect(s.redirect_uri).toBe(`http://localhost:5173${CALLBACK_PATH}`)
    expect(s.automaticSilentRenew).toBe(true)
  })
})

describe('createAuthSession', () => {
  it('hands out the signed-in user’s access token', async () => {
    const { manager } = fakeManager(user())

    expect(await createAuthSession(config, manager).accessToken()).toBe('access-token')
  })

  it('has no token when nobody is signed in', async () => {
    const { manager } = fakeManager(null)

    expect(await createAuthSession(config, manager).accessToken()).toBeNull()
  })

  it('has no token once the one it holds has expired', async () => {
    const { manager } = fakeManager(user({ expired: true }))

    expect(await createAuthSession(config, manager).accessToken()).toBeNull()
  })

  it('signs in by redirecting, remembering where the user was going', async () => {
    const { manager } = fakeManager()

    await createAuthSession(config, manager).signIn('/nodes/Team/new')

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/nodes/Team/new' } })
  })

  it('completes sign-in by returning where the user was going', async () => {
    const { manager } = fakeManager()
    const session = createAuthSession(config, manager)

    expect(await session.completeSignIn()).toBe('/nodes/Team/new')
    expect(session.username.value).toBe('dan')
  })

  it('names the signed-in user once their session loads', async () => {
    const { manager } = fakeManager(user())
    const session = createAuthSession(config, manager)

    await session.accessToken()

    expect(session.username.value).toBe('dan')
  })

  it('knows which graph scopes the signed-in user holds (#116)', async () => {
    const token = accessTokenWith({ sub: 'reader', scope: 'openid graph:read' })
    const { manager } = fakeManager(user({ access_token: token }))
    const session = createAuthSession(config, manager)

    await session.accessToken()

    expect(session.scopes.value).toEqual(['graph:read'])
  })

  it('holds no scopes before a session loads, and learns them when one does', () => {
    const { manager, loaded } = fakeManager(null)
    const session = createAuthSession(config, manager)
    expect(session.scopes.value).toEqual([])

    loaded.forEach(cb =>
      cb(user({ access_token: accessTokenWith({ scope: 'graph:read graph:write' }) }))
    )

    expect(session.scopes.value).toEqual(['graph:read', 'graph:write'])
  })
})
