import { expect, test } from '@playwright/test'

test.describe('walking skeleton', () => {
  test('home page loads and shows the app title', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveTitle(/RepoDataGraph/)
    await expect(page.getByRole('link', { name: /RepoDataGraph/ })).toBeVisible()
    await expect(page.getByRole('heading', { name: 'Git Repositories' })).toBeVisible()
  })

  test('API is reachable through the nginx /api proxy', async ({ request }) => {
    const response = await request.get('/api/v1/repositories')

    expect(response.status()).toBe(200)
    expect(Array.isArray(await response.json())).toBe(true)
  })
})
