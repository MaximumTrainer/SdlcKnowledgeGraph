import { expect, test } from '@playwright/test'

/**
 * The graph is behind a login (#114): a user signs in through Keycloak, the web interface sends
 * their token with every call, and what they write says who wrote it.
 *
 * The development realm (backend/src/acceptanceTest/resources/keycloak/sdlc-realm.json) has a user
 * "dan", whose password is his name, holding both graph scopes.
 */

/** A remote no other run will produce, so the test never trips over a node another run left. */
const uniqueRemote = () =>
  `https://github.com/acme/login-${Date.now()}-${Math.floor(Math.random() * 1000)}`

test('the API refuses a request without a token', async ({ request }) => {
  const response = await request.get('/api/v1/nodes/Repository')

  expect(response.status()).toBe(401)
})

test('the probes stay public', async ({ request }) => {
  const response = await request.get('/actuator/health/liveness')

  expect(response.status()).toBe(200)
})

test('a signed-in user creates a node and sees their name in its provenance', async ({ page }) => {
  const url = uniqueRemote()

  await page.goto('/nodes/Repository/new')

  // Not signed in, so the web interface hands over to the identity provider's login page.
  await expect(page).toHaveURL(/\/realms\/sdlc\/protocol\/openid-connect\/auth/)
  await page.getByLabel('Username').fill('dan')
  await page.getByLabel('Password', { exact: true }).fill('dan')
  await page.getByRole('button', { name: 'Sign In' }).click()

  // And back to the page that was asked for, signed in.
  await expect(page).toHaveURL(/\/nodes\/Repository\/new$/)
  await expect(page.getByTestId('signed-in-user')).toHaveText('dan')

  await page.getByLabel('url').fill(url)
  await page.getByLabel('defaultBranch').fill('main')
  await page.getByRole('button', { name: 'Save' }).click()

  await expect(page).toHaveURL(/\/nodes\/Repository\/github\.com\/acme\/login-/)
  await expect(page.getByTestId('provenance-written-by')).toHaveText('dan')
  await expect(page.getByTestId('provenance-principal-type')).toHaveText('user')
})
