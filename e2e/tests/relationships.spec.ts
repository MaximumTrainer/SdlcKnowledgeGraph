import { expect, test, type Page } from '@playwright/test'

/**
 * Relationships, managed from a node's own page.
 *
 * The panel is driven by the ontology: the edge types it offers are the ones the registry allows for
 * the node being viewed, so it cannot propose a relationship the server would refuse. The last test
 * is the one that matters most — one edge, seen under its own name from one end and under its
 * inverse from the other.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

const createRepository = async (page: Page, name: string) => {
  const url = `https://github.com/acme/${name}`
  await page.goto('/nodes/Repository/new')
  await page.getByLabel('url').fill(url)
  await page.getByLabel('url').fill(`https://github.com/acme/${name}`)
  await page.getByLabel('defaultBranch').fill('main')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(new RegExp(`/nodes/Repository/github.com/acme/${name}$`))
  return `github.com/acme/${name}`
}

const addRelationship = async (page: Page, type: string, targetKey: string, kind?: string) => {
  await page.getByRole('button', { name: 'Add relationship' }).click()
  await page.getByLabel('Relationship').selectOption(type)
  await page.getByLabel('Target').fill(targetKey)
  await page.getByRole('option', { name: targetKey }).click()
  if (kind) await page.getByLabel('kind').selectOption(kind)
  await page.getByRole('button', { name: 'Add', exact: true }).click()
  // The form closes only once the server has accepted it. Without this the test races its own
  // write, and the open form's type dropdown would satisfy an assertion the new edge should.
  await expect(page.getByRole('button', { name: 'Add relationship' })).toBeVisible()
}

/** The listed relationships, excluding the add form. */
const relationships = (page: Page) => page.getByTestId('relationship-list')

test.describe('typed relationships', () => {
  test('only edge types the ontology allows for this node are offered', async ({ page, request }) => {
    const ontology = await (await request.get('/api/v1/ontology')).json()
    // A node may sit at either end, so BUILT_FROM (Artifact -> Repository) belongs here too.
    const allowedForRepository = ontology.edgeTypes
      .filter(
        (edge: { from: string[]; to: string[] }) =>
          edge.from.includes('Repository') || edge.to.includes('Repository')
      )
      .map((edge: { name: string }) => edge.name)

    const key = await createRepository(page, unique('offers'))
    await page.goto(`/nodes/Repository/${key}`)
    await page.getByRole('button', { name: 'Add relationship' }).click()

    const offered = await page.getByLabel('Relationship').locator('option').allTextContents()

    for (const type of allowedForRepository) expect(offered).toContain(type)
    // Deployment -> Environment has a Repository at neither end.
    expect(offered).not.toContain('TO_ENVIRONMENT')
  })

  test('one edge is seen under its own name from one end and its inverse from the other', async ({
    page
  }) => {
    const dependent = await createRepository(page, unique('payments'))
    const dependency = await createRepository(page, unique('shared'))

    await page.goto(`/nodes/Repository/${dependent}`)
    await addRelationship(page, 'DEPENDS_ON', dependency, 'library')

    await expect(relationships(page)).toContainText('DEPENDS_ON')
    await expect(relationships(page)).toContainText(dependency)

    await page.goto(`/nodes/Repository/${dependency}`)
    await expect(relationships(page)).toContainText('DEPENDED_ON_BY')
    await expect(relationships(page)).toContainText(dependent)
  })

  test('removing a relationship clears it from both ends', async ({ page }) => {
    const dependent = await createRepository(page, unique('payments'))
    const dependency = await createRepository(page, unique('shared'))

    await page.goto(`/nodes/Repository/${dependent}`)
    await addRelationship(page, 'DEPENDS_ON', dependency, 'library')
    await page.getByRole('button', { name: `Remove DEPENDS_ON to ${dependency}` }).click()

    await expect(relationships(page)).not.toContainText(dependency)

    await page.goto(`/nodes/Repository/${dependency}`)
    await expect(relationships(page)).not.toContainText(dependent)
  })
})
