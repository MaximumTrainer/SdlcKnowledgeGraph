import type { APIRequestContext, Page } from '@playwright/test'
import { request as apiRequest } from '@playwright/test'
import { expect, test } from './fixtures'
import { freshSession } from '../support/session'
import { markInferred } from '../support/neo4j'

/**
 * The graph view (#9): the neighbourhood of a node drawn with Cytoscape, filtered, deepened,
 * expanded from a node's drawer, and overlaid with the blast radius of #21.
 *
 * A canvas has no accessible tree to assert on, so the view exposes its Cytoscape instance as
 * `window.__cy` when opened with `?e2e=1`, and sets `window.__cyReady` once each layout has stopped.
 * Every assertion on the canvas polls through that hook rather than reading it once.
 *
 * The browser suite shares one graph across tests and workers, so the neighbourhood is seeded under
 * names no other test uses: a payments repository owned by a team, depending on a shared library and
 * owning a bucket through an inferred edge - the issue's background, made unique.
 */
interface CyCollection {
  length: number
  style(name: string): string
}
interface CyElement {
  hasClass(name: string): boolean
  renderedPosition(): { x: number; y: number }
}
interface Cy {
  nodes(selector?: string): CyCollection
  edges(selector?: string): CyCollection & { first(): CyCollection }
  getElementById(id: string): CyElement
}
declare global {
  interface Window {
    __cy?: Cy
    __cyReady?: boolean
  }
}

const suffix = `${Date.now()}-${Math.floor(Math.random() * 100000)}`
const payments = { key: `github.com/acme/gv-payments-${suffix}` }
const sharedLib = { key: `github.com/acme/gv-shared-lib-${suffix}` }
const team = `gv-platform-${suffix}`
const bucket = `arn:aws:s3:::gv-logs-${suffix}`

const PAYMENTS_ID = `Repository:${payments.key}`
const SHARED_LIB_ID = `Repository:${sharedLib.key}`
const TEAM_ID = `Team:${team}`
const BUCKET_ID = `CloudResource:aws:${bucket}`

const created = async (request: APIRequestContext, path: string, data: unknown) => {
  const response = await request.post(path, { data })
  expect(response.status(), `${path}: ${await response.text()}`).toBeLessThan(300)
  return response.json()
}

const repository = (key: string) => ({
  props: { url: `https://${key}`, defaultBranch: 'main', topics: [], codeowners: [] }
})

test.beforeAll(async () => {
  const session = await freshSession()
  const request = await apiRequest.newContext({
    baseURL: 'http://localhost:5173',
    extraHTTPHeaders: { Authorization: `Bearer ${String(session.user.access_token)}` }
  })
  try {
    await created(request, '/api/v1/nodes/Repository', repository(payments.key))
    await created(request, '/api/v1/nodes/Repository', repository(sharedLib.key))
    await created(request, '/api/v1/nodes/Team', { props: { name: team } })
    await created(request, '/api/v1/nodes/CloudResource', {
      props: { provider: 'aws', resourceId: bucket, resourceType: 's3', name: `gv-logs-${suffix}` }
    })
    await created(request, '/api/v1/edges', {
      type: 'OWNED_BY',
      fromId: PAYMENTS_ID,
      toId: TEAM_ID
    })
    await created(request, '/api/v1/edges', {
      type: 'DEPENDS_ON',
      fromId: PAYMENTS_ID,
      toId: SHARED_LIB_ID,
      props: { kind: 'library' }
    })
    await created(request, '/api/v1/edges', {
      type: 'OWNS_RESOURCE',
      fromId: PAYMENTS_ID,
      toId: BUCKET_ID,
      props: { rule: 'tag' }
    })
  } finally {
    await request.dispose()
  }
  await markInferred('OWNS_RESOURCE', PAYMENTS_ID, BUCKET_ID, 0.7)
})

/**
 * Opens the graph view of the payments repository, with its test hook, and waits for the layout and
 * for everything above the canvas that fills in from the ontology and moves the canvas down when it
 * arrives: the header's type navigation and the view's type filters, each fetched on its own.
 */
const openGraph = async (page: Page) => {
  const [type, ...key] = PAYMENTS_ID.split(':')
  await page.goto(`/graph/${type}:${encodeURIComponent(key.join(':'))}?e2e=1`)
  await page.waitForFunction(() => window.__cyReady === true)
  await expect(
    page.getByRole('navigation', { name: 'Node types' }).getByRole('link', {
      name: 'Repository',
      exact: true
    })
  ).toBeVisible()
  await expect(
    page.getByRole('group', { name: 'Node types' }).getByLabel('Repository', { exact: true })
  ).toBeVisible()
}

const nodeCount = (page: Page) => page.evaluate(() => window.__cy?.nodes().length ?? 0)
const edgeCount = (page: Page) => page.evaluate(() => window.__cy?.edges().length ?? 0)

/** Clicks a node where Cytoscape drew it, as a person would. */
const clickNode = async (page: Page, id: string) => {
  const canvas = page.getByTestId('graph-canvas')
  await canvas.scrollIntoViewIfNeeded()
  const box = await canvas.boundingBox()
  expect(box, 'the canvas is on the page').not.toBeNull()
  const position = await page.evaluate(
    nodeId => window.__cy?.getElementById(nodeId).renderedPosition(),
    id
  )
  expect(position, `${id} is drawn`).toBeDefined()
  await page.mouse.click(box!.x + position!.x, box!.y + position!.y)
}

test.describe('graph view', () => {
  test('draws the depth-1 neighbourhood, with the inferred edge dashed', async ({ page }) => {
    await openGraph(page)

    await expect.poll(() => nodeCount(page)).toBe(4)
    await expect.poll(() => edgeCount(page)).toBe(3)
    await expect.poll(() => page.evaluate(() => window.__cy?.edges('[inferred]').length)).toBe(1)
    expect(
      await page.evaluate(() => window.__cy?.edges('[inferred]').first().style('line-style'))
    ).toBe('dashed')
    await expect(page.getByTestId('graph-legend')).toContainText('inferred')
  })

  test('unticking a node type narrows the canvas and the link', async ({ page }) => {
    await openGraph(page)
    await expect.poll(() => nodeCount(page)).toBe(4)

    await page
      .getByRole('group', { name: 'Node types' })
      .getByLabel('CloudResource', { exact: true })
      .uncheck()

    await expect.poll(() => nodeCount(page)).toBe(3)
    await expect(page).toHaveURL(/nodeTypes=Repository,Team(,|&|$)/)
    const shown = new URL(page.url()).searchParams.get('nodeTypes')?.split(',') ?? []
    expect(shown).not.toContain('CloudResource')
  })

  test('moving the depth slider fetches the deeper neighbourhood', async ({ page }) => {
    await openGraph(page)

    const deeper = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname.endsWith('/graph/neighbourhood') && url.searchParams.get('depth') === '2'
    })
    const slider = page.getByLabel('Depth')
    await slider.focus()
    await page.keyboard.press('ArrowRight')

    expect((await deeper).status()).toBe(200)
    await expect(page).toHaveURL(/depth=2/)
  })

  test('a node opens its drawer, and expanding it never loses what is drawn', async ({ page }) => {
    await openGraph(page)
    await expect.poll(() => nodeCount(page)).toBe(4)

    await clickNode(page, SHARED_LIB_ID)

    const drawer = page.getByTestId('node-drawer')
    await expect(drawer).toContainText(sharedLib.key)
    await expect(drawer.getByTestId('drawer-source-system')).toHaveText('manual')

    await drawer.getByRole('button', { name: 'Expand' }).click()
    await page.waitForFunction(() => window.__cyReady === true)
    expect(await nodeCount(page)).toBeGreaterThanOrEqual(4)
  })

  test('the blast radius marks what a change to the selected node reaches', async ({ page }) => {
    await openGraph(page)
    await expect.poll(() => nodeCount(page)).toBe(4)
    await clickNode(page, SHARED_LIB_ID)
    await expect(page.getByTestId('node-drawer')).toContainText(sharedLib.key)

    await page.getByRole('checkbox', { name: 'Blast radius' }).check()

    await expect
      .poll(() =>
        page.evaluate(id => window.__cy?.getElementById(id).hasClass('affected'), PAYMENTS_ID)
      )
      .toBe(true)
    await expect(page.getByTestId('blast-radius-badge')).toContainText('Repository: 1')
  })
})
