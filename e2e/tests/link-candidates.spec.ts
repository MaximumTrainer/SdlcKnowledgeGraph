import type { APIRequestContext, Page } from '@playwright/test'
import { expect, test } from './fixtures'

/**
 * The review of the link engine's candidates (#28, FR12), against the real stack.
 *
 * The stack runs no cloud connector, so the evidence a connector would record is stated through the
 * API: a repository, a queue named after it, and for the conflict a tagged site that another
 * repository's infrastructure as code also names. A resolution is then asked for and waited on
 * through the sync run it answers with, as the page's own "Run resolution" button does.
 *
 * Every name is unique to the test, so the tests run beside each other and again without meeting:
 * a resolution reads the whole graph, but decides each resource on its own evidence.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 100000)}`

const createRepository = async (request: APIRequestContext, name: string) => {
  const key = `github.com/acme/${name}`
  const response = await request.post('/api/v1/nodes/Repository', {
    data: {
      props: { url: `https://${key}`, defaultBranch: 'main', topics: [], codeowners: [] }
    }
  })
  expect(response.status(), await response.text()).toBe(201)
  return key
}

const createResource = async (
  request: APIRequestContext,
  props: { provider: string; resourceId: string; resourceType: string; name: string },
  tags: string[] = []
) => {
  const response = await request.post('/api/v1/nodes/CloudResource', {
    data: { props: { ...props, tags } }
  })
  expect(response.status(), await response.text()).toBe(201)
  return `${props.provider}:${props.resourceId}`
}

/** Asks for a resolution and waits until its run has finished. */
const resolve = async (request: APIRequestContext) => {
  const started = await request.post('/api/v1/links/resolve', { data: {} })
  expect(started.status(), await started.text()).toBe(202)
  const { syncRunId } = await started.json()
  await expect(async () => {
    const run = await (await request.get(`/api/v1/sync-runs/${syncRunId}`)).json()
    expect(run.status).toBe('SUCCESS')
  }).toPass({ timeout: 30_000 })
}

/** A queue named after a new repository: evidence only the naming rule reads. */
const namedQueue = async (request: APIRequestContext) => {
  const name = unique('billing')
  const repository = await createRepository(request, name)
  const queueName = `${name}-prod`
  const queue = await createResource(request, {
    provider: 'aws',
    resourceId: `arn:aws:sqs:eu-west-1:111111111111:${queueName}`,
    resourceType: 'sqs',
    name: queueName
  })
  return { repository, queue, queueName }
}

const search = async (page: Page, text: string) => {
  await page.getByLabel('Search').fill(text)
}

test.describe('link candidates', () => {
  test('the page is reachable from the navigation', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: 'Links', exact: true }).click()

    await expect(page).toHaveURL(/\/links\/candidates$/)
    await expect(page.getByRole('heading', { name: 'Link candidates' })).toBeVisible()
  })

  test('a naming candidate shows its rule, a 40% confidence bar and its evidence', async ({
    page,
    request
  }) => {
    const { queueName, repository } = await namedQueue(request)
    await resolve(request)

    await page.goto('/links/candidates')
    await search(page, queueName)
    const row = page.getByTestId('candidate').filter({ hasText: queueName })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(repository)
    await expect(row.getByTestId('rule-badge')).toHaveText('naming')
    await expect(row.getByTestId('confidence-bar')).toHaveAttribute('aria-valuenow', '40')
    await expect(row.getByTestId('confidence-bar')).toContainText('40%')

    await row.getByRole('button', { name: 'Evidence' }).click()
    await expect(page.getByTestId('evidence-list')).toContainText(queueName)
  })

  test('accepting a candidate removes it from pending and makes a stated owner', async ({
    page,
    request
  }) => {
    const { queue, queueName, repository } = await namedQueue(request)
    await resolve(request)

    await page.goto('/links/candidates')
    await search(page, queueName)
    const row = page.getByTestId('candidate').filter({ hasText: queueName })
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: 'Accept' }).click()
    await expect(row).toHaveCount(0)

    await page.goto(`/nodes/Repository/${repository}`)
    // Under OWNS_RESOURCE: the accepted candidate is still listed under MAY_OWN, closed, as history.
    const owned = page
      .getByTestId('relationship-list')
      .locator('[data-test="relationship"][data-relationship="OWNS_RESOURCE"]')
      .filter({ hasText: queue })
    await expect(owned).toHaveCount(1)
    await expect(owned.getByTestId('inferred-badge')).toHaveCount(0)
  })

  test('filtering by status conflict shows only conflicts', async ({ page, request }) => {
    const owner = await createRepository(request, unique('payments'))
    const rival = await createRepository(request, unique('rival'))
    const siteName = unique('pay')
    await createResource(
      request,
      {
        provider: 'azure',
        resourceId: `/subscriptions/s/resourcegroups/rg/providers/microsoft.web/sites/${siteName}`,
        resourceType: 'sites',
        name: siteName
      },
      [`repo=${owner}`]
    )
    const iac = await request.post('/api/v1/nodes/IacFile', {
      data: {
        props: {
          repoKey: rival,
          path: 'infra/app.bicep',
          format: 'bicep',
          resourceRefs: [siteName]
        }
      }
    })
    expect(iac.status(), await iac.text()).toBe(201)
    await resolve(request)

    await page.goto('/links/candidates')
    await page.getByLabel('Status').selectOption('conflict')
    await search(page, siteName)
    const row = page.getByTestId('candidate').filter({ hasText: siteName })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(rival)
    // Only once the search has answered: the status filter's own answer lists every conflict.
    await expect(page.getByTestId('candidate')).toHaveCount(1)
    for (const status of await page.getByTestId('candidate-status').all()) {
      await expect(status).toHaveText('conflict')
    }
  })

  test('Run resolution starts a run and lists what it found', async ({ page, request }) => {
    const { queueName } = await namedQueue(request)

    await page.goto('/links/candidates')
    await search(page, queueName)
    await page.getByRole('button', { name: 'Run resolution' }).click()

    await expect(page.getByTestId('candidate').filter({ hasText: queueName })).toHaveCount(1, {
      timeout: 30_000
    })
  })
})
