import { expect, request as apiRequest, type Page } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Signing in, for the browser suite (#118). The default stack is behind a login: Keycloak with the
 * development realm (backend/src/acceptanceTest/resources/keycloak/sdlc-realm.json), whose users
 * sign in with their own name as their password.
 *
 * `auth.setup.ts` signs dan in once, through the real login page, and keeps the session the web
 * interface stored. Each test then starts from a fresh copy of that session, renewed with its refresh
 * token, so a test is signed in from its first page without going through the login again and never
 * holds an access token that expires halfway through a run.
 */

/** Where Keycloak answers the browser, and so the issuer every token names. */
export const KEYCLOAK_URL = process.env.KEYCLOAK_URL ?? 'http://localhost:8081'
export const REALM_URL = `${KEYCLOAK_URL}/realms/sdlc`
export const TOKEN_ENDPOINT = `${REALM_URL}/protocol/openid-connect/token`

/** The public client the web interface signs in with. */
const CLIENT_ID = 'sdlc-ui'

/** Where the setup project leaves dan's session. Outside tests/, and ignored by git. */
export const SAVED_SESSION = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '.auth',
  'dan.json'
)

/** The sessionStorage entry oidc-client-ts keeps a signed-in user under. */
export interface StoredSession {
  key: string
  user: Record<string, unknown>
}

/** Signs [username] in through the web interface's login page, landing on [target]. */
export async function signIn(page: Page, username: string, target = '/'): Promise<void> {
  await page.goto(target)
  // Not signed in, so the web interface hands over to the identity provider's login page.
  await expect(page).toHaveURL(/\/realms\/sdlc\/protocol\/openid-connect\/auth/)
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password', { exact: true }).fill(username)
  await page.getByRole('button', { name: 'Sign In' }).click()
  await expect(page.getByTestId('signed-in-user')).toHaveText(username)
}

/** The session the web interface stored for the signed-in user, read from the page. */
export async function storedSession(page: Page): Promise<StoredSession> {
  const stored = await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(name => name.startsWith('oidc.user:'))
    return key ? { key, user: JSON.parse(sessionStorage.getItem(key) ?? '{}') } : null
  })
  expect(stored, 'the web interface stored no session').not.toBeNull()
  return stored as StoredSession
}

/** The access token of the user signed in on [page]. */
export async function accessTokenOf(page: Page): Promise<string> {
  return String((await storedSession(page)).user.access_token)
}

/**
 * dan's saved session with fresh tokens: the refresh token the setup project kept is exchanged for a
 * new access token, as the web interface's own silent renewal would.
 */
export async function freshSession(): Promise<StoredSession> {
  const saved = JSON.parse(await readFile(SAVED_SESSION, 'utf8')) as StoredSession
  const context = await apiRequest.newContext()
  try {
    const response = await context.post(TOKEN_ENDPOINT, {
      form: {
        grant_type: 'refresh_token',
        client_id: CLIENT_ID,
        refresh_token: String(saved.user.refresh_token)
      }
    })
    expect(response.ok(), `refreshing dan's session: ${response.status()}`).toBeTruthy()
    const tokens = await response.json()
    return {
      key: saved.key,
      user: {
        ...saved.user,
        access_token: tokens.access_token,
        refresh_token: tokens.refresh_token ?? saved.user.refresh_token,
        id_token: tokens.id_token ?? saved.user.id_token,
        scope: tokens.scope ?? saved.user.scope,
        expires_at: Math.floor(Date.now() / 1000) + Number(tokens.expires_in)
      }
    }
  } finally {
    await context.dispose()
  }
}
