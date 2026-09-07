import { expect, test, type Page } from '@playwright/test'

/**
 * The editing surface is rendered from GET /api/v1/ontology rather than hand-written per type, so
 * these tests assert the screens follow the registry: the tabs are the registry's node types, and a
 * form's fields are the type's declared properties.
 *
 * The bug this issue closes is in the last test: saving an edit used to create a second node.
 */

/** A name no other run will collide with, since the stack is not reset between tests. */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

const rowCount = (page: Page) => page.getByRole('row').filter({ hasNot: page.locator('th') }).count()

test.describe('ontology-driven node CRUD', () => {
  test('the type tabs are the node types the ontology declares', async ({ page, request }) => {
    const ontology = await (await request.get('/api/v1/ontology')).json()
    const declared: string[] = ontology.nodeTypes.map((type: { name: string }) => type.name)

    await page.goto('/nodes/Repository')

    for (const name of declared) {
      await expect(page.getByRole('link', { name, exact: true })).toBeVisible()
    }
  })

  test('/ redirects to the Repository list', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveURL(/\/nodes\/Repository$/)
  })

  test('a new node appears in the list', async ({ page }) => {
    await page.goto('/nodes/Team')
    const before = await rowCount(page)

    await page.getByRole('link', { name: 'New' }).click()
    await page.getByLabel('name').fill(unique('platform'))
    await page.getByRole('button', { name: 'Save' }).click()

    await page.goto('/nodes/Team')
    expect(await rowCount(page)).toBe(before + 1)
  })

  test('a required property is refused before any request is sent', async ({ page }) => {
    let requested = false
    await page.route('**/api/v1/nodes/Team', route => {
      if (route.request().method() === 'POST') requested = true
      return route.continue()
    })

    await page.goto('/nodes/Team/new')
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page.getByText('name is required')).toBeVisible()
    expect(requested).toBe(false)
  })

  test('editing a node changes it rather than duplicating it', async ({ page }) => {
    const name = unique('platform')

    await page.goto('/nodes/Team/new')
    await page.getByLabel('name').fill(name)
    await page.getByRole('button', { name: 'Save' }).click()

    await page.goto('/nodes/Team')
    const afterCreate = await rowCount(page)

    await page.getByRole('link', { name }).click()
    await page.getByRole('link', { name: 'Edit' }).click()
    await page.getByLabel('email').fill('platform@acme.example')
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page.getByText('platform@acme.example')).toBeVisible()

    await page.goto('/nodes/Team')
    expect(await rowCount(page)).toBe(afterCreate)
  })

  test('an identity property cannot be edited', async ({ page }) => {
    const name = unique('platform')

    await page.goto('/nodes/Team/new')
    await page.getByLabel('name').fill(name)
    await page.getByRole('button', { name: 'Save' }).click()

    await page.getByRole('link', { name: 'Edit' }).click()

    await expect(page.getByLabel('name')).toBeDisabled()
  })
})
