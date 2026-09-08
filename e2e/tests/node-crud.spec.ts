import { expect, test, type Page } from '@playwright/test'

/**
 * The editing surface is rendered from GET /api/v1/ontology rather than hand-written per type, so
 * these tests assert the screens follow the registry: the tabs are the registry's node types, and a
 * form's fields are the type's declared properties.
 *
 * The bug this issue closes is the last test: saving an edit used to create a second node.
 *
 * The stack is shared and not reset between tests, so nothing here counts rows or assumes it is the
 * only writer. Each test works with a name no other run will produce, and asserts on that.
 */

/** A name no other run will produce. Team keys are the lowercased name, so this is the key too. */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

/**
 * Saves, and waits for the navigation the editor performs on success.
 *
 * Without this a test races its own write: clicking Save only dispatches the request, so navigating
 * straight to the list can read the graph before the node reaches it.
 */
const save = async (page: Page, key: string) => {
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(new RegExp(`/nodes/Team/${key}$`))
}

const createTeam = async (page: Page, name: string) => {
  await page.goto('/nodes/Team/new')
  await page.getByLabel('name').fill(name)
  await save(page, name)
}

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
    const name = unique('platform')

    await createTeam(page, name)

    await page.goto('/nodes/Team')
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(1)
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
    await createTeam(page, name)

    await page.getByRole('link', { name: 'Edit' }).click()
    await page.getByLabel('email').fill('platform@acme.example')
    await save(page, name)

    await expect(page.getByText('platform@acme.example')).toBeVisible()

    // The bug: the hand-written editor called create in both modes, so this was 2.
    await page.goto('/nodes/Team')
    await expect(page.getByRole('link', { name, exact: true })).toHaveCount(1)
  })

  test('an identity property cannot be edited', async ({ page }) => {
    await createTeam(page, unique('platform'))

    await page.getByRole('link', { name: 'Edit' }).click()

    await expect(page.getByLabel('name')).toBeDisabled()
    await expect(page.getByLabel('email')).toBeEnabled()
  })
})
