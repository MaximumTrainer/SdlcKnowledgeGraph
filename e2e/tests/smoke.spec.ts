import { expect, test } from './fixtures'
import { signIn } from '../support/session'

/** A remote no other run will produce, so the test never trips over a node another run left. */
const uniqueRemote = () =>
  `https://github.com/acme/smoke-${Date.now()}-${Math.floor(Math.random() * 1000)}`

test.describe('walking skeleton', () => {
  test('home page loads and shows the app title', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveTitle(/RepoDataGraph/)
    await expect(page.getByRole('link', { name: /RepoDataGraph/ })).toBeVisible()
    // The landing route redirects to the Repository list, whose heading is the registry type (#4).
    await expect(page.getByRole('heading', { name: 'Repository', level: 1 })).toBeVisible()
  })

  test('API is reachable through the nginx /api proxy', async ({ request }) => {
    const response = await request.get('/api/v1/repositories')

    expect(response.status()).toBe(200)
    expect(Array.isArray(await response.json())).toBe(true)
  })
})

/**
 * The default stack is behind a login (#114, #118): a fresh visitor is sent to Keycloak, signs in
 * with the seeded development user (dan / dan), and what they write says who wrote it.
 */
test.describe('signing in', () => {
  test.use({ signedIn: false })

  test('the API refuses a request without a token', async ({ request }) => {
    const response = await request.get('/api/v1/nodes/Repository')

    expect(response.status()).toBe(401)
  })

  test('the probes stay public', async ({ request }) => {
    const response = await request.get('/actuator/health/liveness')

    expect(response.status()).toBe(200)
  })

  test('a visitor signs in through Keycloak, creates a node and reads their name in its provenance', async ({
    page
  }) => {
    const url = uniqueRemote()

    // Signed out, so the web interface hands over to Keycloak's login page, and back afterwards.
    await signIn(page, 'dan', '/nodes/Repository/new')
    await expect(page).toHaveURL(/\/nodes\/Repository\/new$/)

    await page.getByLabel('url').fill(url)
    await page.getByLabel('defaultBranch').fill('main')
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page).toHaveURL(/\/nodes\/Repository\/github\.com\/acme\/smoke-/)
    await expect(page.getByTestId('provenance-written-by')).toHaveText('dan')
    await expect(page.getByTestId('provenance-principal-type')).toHaveText('user')
  })
})
