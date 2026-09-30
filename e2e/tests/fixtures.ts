import { test as base } from '@playwright/test'
import { freshSession, type StoredSession } from '../support/session'

export { expect } from '@playwright/test'

/**
 * The browser suite's `test`, signed in as dan (#118).
 *
 * Every page starts with dan's session already in the web interface's sessionStorage, and `request`
 * sends his access token, so a test reads and writes as a signed-in user without repeating the login
 * that `auth.setup.ts` and the smoke test go through. A test that needs to start signed out says so
 * with `test.use({ signedIn: false })`.
 */
export const test = base.extend<{ signedIn: boolean; session: StoredSession | null }>({
  signedIn: [true, { option: true }],

  session: async ({ signedIn }, use) => {
    await use(signedIn ? await freshSession() : null)
  },

  context: async ({ context, session, baseURL }, use) => {
    if (session) {
      const origin = new URL(baseURL ?? 'http://localhost:5173').origin
      await context.addInitScript(
        ({ origin, key, value }) => {
          // Only on the web interface's own pages: Keycloak's have a sessionStorage of their own.
          if (window.location.origin === origin) window.sessionStorage.setItem(key, value)
        },
        { origin, key: session.key, value: JSON.stringify(session.user) }
      )
    }
    await use(context)
  },

  request: async ({ playwright, baseURL, session }, use) => {
    const context = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: session
        ? { Authorization: `Bearer ${String(session.user.access_token)}` }
        : {}
    })
    await use(context)
    await context.dispose()
  }
})
