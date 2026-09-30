import type { Router } from 'vue-router'
import { CALLBACK_PATH, type AuthSession } from './session'

/**
 * Sends a user who is not signed in to the identity provider before any page loads, so no page
 * renders half-empty from calls the API refused (#114, FR-2). The callback route is exempt: it is
 * where a sign-in completes.
 */
export const guardRoutes = (router: Router, session: AuthSession): void => {
  router.beforeEach(async to => {
    if (to.path === CALLBACK_PATH) return true
    if (await session.accessToken()) return true
    await session.signIn(to.fullPath)
    return false
  })
}
