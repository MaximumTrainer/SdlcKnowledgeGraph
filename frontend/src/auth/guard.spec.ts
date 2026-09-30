import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { guardRoutes } from './guard'
import { CALLBACK_PATH, type AuthSession } from './session'

/** An unauthenticated user is sent to the identity provider before any page loads (#114, FR-2). */
const session = (token: string | null): AuthSession => ({
  username: ref(null),
  scopes: ref([]),
  accessToken: vi.fn(async () => token),
  signIn: vi.fn(async () => undefined),
  completeSignIn: vi.fn(async () => '/'),
  signOut: vi.fn(async () => undefined)
})

const routerWith = (s: AuthSession) => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<p>home</p>' } },
      { path: '/nodes/:type/new', component: { template: '<p>new</p>' } },
      { path: CALLBACK_PATH, component: { template: '<p>callback</p>' } }
    ]
  })
  guardRoutes(router, s)
  return router
}

describe('guardRoutes', () => {
  it('sends a user who is not signed in to the login, remembering the page', async () => {
    const s = session(null)
    const router = routerWith(s)

    await router.push('/nodes/Team/new')

    expect(s.signIn).toHaveBeenCalledWith('/nodes/Team/new')
    expect(router.currentRoute.value.path).not.toBe('/nodes/Team/new')
  })

  it('lets a signed-in user through', async () => {
    const s = session('access-token')
    const router = routerWith(s)

    await router.push('/nodes/Team/new')

    expect(router.currentRoute.value.path).toBe('/nodes/Team/new')
    expect(s.signIn).not.toHaveBeenCalled()
  })

  it('always lets the login callback through, which is how a user becomes signed in', async () => {
    const s = session(null)
    const router = routerWith(s)

    await router.push(CALLBACK_PATH)

    expect(router.currentRoute.value.path).toBe(CALLBACK_PATH)
  })
})
