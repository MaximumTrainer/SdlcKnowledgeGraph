import { expect, test } from './fixtures'
import { cypher } from '../support/neo4j'

/**
 * Freshness in the web interface (#93, FR-6): a fact its source has not re-stated within the
 * source's freshness window is marked stale on its page, and the home page says how far behind each
 * source is.
 *
 * Nothing in the API writes an old fact or an old sync run, so both are backdated in Neo4j after the
 * fact, the way the graph view's spec marks an edge inferred. The stack is shared, so the stale team
 * has a name no other run produces, and the sync run belongs to a connector no other test knows and
 * is removed afterwards.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

const DAY_MS = 24 * 60 * 60 * 1000

test.describe('freshness', () => {
  test('a fact older than its source window is marked stale, and a fresh one is not', async ({
    page,
    request
  }) => {
    const stale = unique('stale-team')
    const fresh = unique('fresh-team')
    for (const name of [stale, fresh]) {
      const created = await request.post('/api/v1/nodes/Team', { data: { props: { name } } })
      expect(created.status(), await created.text()).toBe(201)
    }
    // Two days ago: past the default window of a day that manual facts get.
    const rows = await cypher(
      'MATCH (n:Team { key: $key }) SET n.prov_ingestedAt = datetime($at) RETURN count(n)',
      { key: stale, at: new Date(Date.now() - 2 * DAY_MS).toISOString() }
    )
    expect(rows[0][0]).toBe(1)

    await page.goto(`/nodes/Team/${stale}`)
    await expect(page.getByTestId('provenance-stale')).toHaveText(/stale/i)

    await page.goto(`/nodes/Team/${fresh}`)
    await expect(page.getByTestId('provenance')).toBeVisible()
    await expect(page.getByTestId('provenance-stale')).toHaveCount(0)
  })

  test.describe('a source behind its window', () => {
    const run = unique('e2e-lag-run')

    test.beforeAll(async () => {
      // An aws run that finished thirty hours ago, past the default window of a day.
      await cypher(
        `CREATE (r:SyncRun { key: $run, id: 'SyncRun:' + $run, connector: 'e2e-lag', sourceSystem: 'aws',
                             mode: 'FULL', status: 'SUCCESS', startedAt: datetime($at), finishedAt: datetime($at) })`,
        { run, at: new Date(Date.now() - 30 * 60 * 60 * 1000).toISOString() }
      )
    })

    test.afterAll(async () => {
      await cypher('MATCH (r:SyncRun { key: $run }) DETACH DELETE r', { run })
    })

    test('is shown as behind on the home page', async ({ page }) => {
      await page.goto('/')

      const aws = page.getByTestId('source-lag-aws')
      await expect(aws).toBeVisible()
      await expect(aws).toContainText('aws')
      await expect(aws).toContainText(/behind/i)
    })
  })
})
