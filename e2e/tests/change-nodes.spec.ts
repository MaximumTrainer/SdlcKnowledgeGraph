import type { APIRequestContext, Page } from '@playwright/test'
import { expect, test } from './fixtures'

/**
 * Changes, pull requests and the work items they implement (#85), maintained through the generic
 * editor with no screen of their own: the form is rendered from the registry, like every other type.
 *
 * Two things are new to the editor here. A Change's `committedAt` is a required instant, entered in
 * a `datetime-local` input and read as UTC, as the run history's filters are. And an
 * ExternalWorkItem's identity is its URI, which holds `//` - something no path segment may carry - so
 * its page is addressed by the URI encoded as one segment, and the API is asked for it by key.
 *
 * The stack is shared and not reset between tests, so every repository, sha and URI is unique.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 100000)}`

/** A short hex sha no other run will produce. */
const uniqueSha = () =>
  `${Date.now().toString(16)}${Math.floor(Math.random() * 0xffff)
    .toString(16)
    .padStart(4, '0')}`

const created = async (request: APIRequestContext, path: string, data: unknown) => {
  const response = await request.post(path, { data })
  expect(response.status(), `${path}: ${await response.text()}`).toBeLessThan(300)
  return response.json()
}

/** Saves, and waits for the navigation the editor performs on success. */
const save = async (page: Page, url: RegExp) => {
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page).toHaveURL(url)
}

const escaped = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

test.describe('change lineage nodes', () => {
  test('a Change is created through the generic editor', async ({ page }) => {
    const repositoryKey = `github.com/acme/${unique('payments')}`
    const sha = uniqueSha()

    await page.goto('/nodes/Change/new')
    await page.getByLabel(/^sha/).fill(sha)
    await page.getByLabel('repositoryKey').fill(repositoryKey)
    await page.getByLabel('committedAt').fill('2026-09-13T10:00')
    await page.getByLabel('title').fill('Retry the payment webhook')
    await save(page, new RegExp(`/nodes/Change/${escaped(`${repositoryKey}@${sha}`)}$`))

    await expect(page.getByRole('heading', { name: `${repositoryKey}@${sha}` })).toBeVisible()
    // The input is labelled as UTC and stored as the instant it names, whatever the browser's zone.
    await expect(page.getByText('2026-09-13T10:00:00Z')).toBeVisible()
    await expect(page.getByText('Retry the payment webhook')).toBeVisible()
  })

  test('an ExternalWorkItem, identified by its URI, opens on its own page', async ({ page }) => {
    const uri = `chorus://task/${unique('01J')}`

    await page.goto('/nodes/ExternalWorkItem/new')
    await page.getByLabel('uri').fill(uri)
    await page.getByLabel('system').fill('chorus')
    await page.getByLabel('key', { exact: true }).fill('CH-42')
    await save(page, new RegExp(`/nodes/ExternalWorkItem/${escaped(encodeURIComponent(uri))}$`))

    await expect(page.getByRole('heading', { name: uri })).toBeVisible()
    await expect(page.getByText('CH-42')).toBeVisible()

    // Its identity cannot be edited, and the edit form finds the node the URI names.
    await page.getByRole('link', { name: 'Edit' }).click()
    await expect(page.getByLabel('uri')).toHaveValue(uri)
    await expect(page.getByLabel('uri')).toBeDisabled()
  })

  test("a change's page links to the work item it implements, and back", async ({
    page,
    request
  }) => {
    const repositoryKey = `github.com/acme/${unique('payments')}`
    const sha = uniqueSha()
    const uri = `chorus://task/${unique('01J')}`
    const change = await created(request, '/api/v1/nodes/Change', {
      props: { repositoryKey, sha, committedAt: '2026-09-13T10:00:00Z' }
    })
    const workItem = await created(request, '/api/v1/nodes/ExternalWorkItem', {
      props: { uri, system: 'chorus' }
    })
    await created(request, '/api/v1/edges', {
      type: 'IMPLEMENTS',
      fromId: change.id,
      toId: workItem.id
    })

    await page.goto(`/nodes/Change/${change.key}`)
    const relationships = page.getByTestId('relationship-list')
    await expect(relationships.getByRole('heading', { name: 'IMPLEMENTS' })).toBeVisible()
    await relationships.getByRole('link', { name: uri }).click()

    await expect(page).toHaveURL(
      new RegExp(`/nodes/ExternalWorkItem/${escaped(encodeURIComponent(uri))}$`)
    )
    await expect(page.getByRole('heading', { name: uri })).toBeVisible()
    await expect(
      page.getByTestId('relationship-list').getByRole('heading', { name: 'IMPLEMENTED_BY' })
    ).toBeVisible()
    await expect(
      page.getByTestId('relationship-list').getByRole('link', { name: `${repositoryKey}@${sha}` })
    ).toBeVisible()
  })
})
