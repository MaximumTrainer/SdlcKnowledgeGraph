import { expect, test, type Page } from '@playwright/test'
import { accessTokenOf, signIn } from '../support/session'

/**
 * Least privilege (#116): what a principal may do comes from the graph scopes on its token. The
 * development realm's "reader" (password "reader") holds graph:read only, so the web interface
 * offers them no way to change the graph, and the API refuses a write that gets through anyway,
 * saying which scope it needed and which the token held.
 *
 * Each test signs its own user in, so it uses Playwright's own `test` rather than the suite's
 * fixture, which would start it signed in as dan.
 */

/** Signs [username] in through the web interface, landing on [path], and hands back their token. */
async function signInAs(page: Page, username: string, path = '/'): Promise<string> {
  await signIn(page, username, path)
  return accessTokenOf(page)
}

test('a read-only user reads the graph and is offered no way to change it', async ({ page }) => {
  await signInAs(page, 'reader', '/nodes/Team')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByTestId('new-node')).toHaveCount(0)
})

test('a user who may write is still offered the way to', async ({ page }) => {
  await signInAs(page, 'dan', '/nodes/Team')

  await expect(page.getByTestId('new-node')).toBeVisible()
})

test('a write from a read-only user is refused, saying which scope it needed', async ({
  page,
  request
}) => {
  const token = await signInAs(page, 'reader')

  const response = await request.post('/api/v1/nodes/Team', {
    headers: { Authorization: `Bearer ${token}` },
    data: { props: { name: `read-only-${Date.now()}` } }
  })

  expect(response.status()).toBe(403)
  expect(await response.json()).toEqual({
    error: 'insufficient scope',
    required: ['graph:write'],
    held: ['graph:read']
  })
})
