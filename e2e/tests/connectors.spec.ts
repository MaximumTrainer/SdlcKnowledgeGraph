import { expect, test } from '@playwright/test'

/**
 * The connectors screen, against the real stack.
 *
 * No connector to a real system exists yet (#23 onwards), so what this proves is the part a person
 * meets when none is configured: the screen loads, and it says plainly that nothing is ingesting
 * rather than showing an empty table that could equally mean the page is broken (#22).
 */
test.describe('connectors', () => {
  test('the screen is reachable from the navigation', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: 'Connectors' }).click()

    await expect(page).toHaveURL(/\/connectors$/)
    await expect(page.getByRole('heading', { name: 'Connectors' })).toBeVisible()
  })

  test('says so when nothing is ingesting, rather than showing an empty table', async ({ page }) => {
    await page.goto('/connectors')

    // Either a connector is configured and listed, or the screen states that none is. An empty page
    // with neither is the failure this asserts against.
    const listed = page.locator('tbody tr')
    const none = page.getByTestId('no-connectors')
    await expect(listed.first().or(none)).toBeVisible()
  })
})
