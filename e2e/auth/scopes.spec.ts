import { expect, test, type Page } from '@playwright/test'

/**
 * Least privilege (#116): what a principal may do comes from the graph scopes on its token. The
 * development realm's "reader" (password "reader") holds graph:read only, so the web interface
 * offers them no way to change the graph, and the API refuses a write that gets through anyway,
 * saying which scope it needed and which the token held.
 */

/** Signs [username] in through the web interface, landing on [path], and hands back their token. */
async function signIn(page: Page, username: string, path = '/'): Promise<string> {
  await page.goto(path)
  await expect(page).toHaveURL(/\/realms\/sdlc\/protocol\/openid-connect\/auth/)
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password', { exact: true }).fill(username)
  await page.getByRole('button', { name: 'Sign In' }).click()
  await expect(page.getByTestId('signed-in-user')).toHaveText(username)
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(name => name.startsWith('oidc.user:'))
    return key ? (JSON.parse(sessionStorage.getItem(key) ?? '{}').access_token as string) : ''
  })
}

test('a read-only user reads the graph and is offered no way to change it', async ({ page }) => {
  await signIn(page, 'reader', '/nodes/Team')

  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.getByTestId('new-node')).toHaveCount(0)
})

test('a user who may write is still offered the way to', async ({ page }) => {
  await signIn(page, 'dan', '/nodes/Team')

  await expect(page.getByTestId('new-node')).toBeVisible()
})

test('a write from a read-only user is refused, saying which scope it needed', async ({
  page,
  request
}) => {
  const token = await signIn(page, 'reader')

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
