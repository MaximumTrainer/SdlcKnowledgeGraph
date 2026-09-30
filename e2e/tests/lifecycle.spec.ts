import { expect, test } from './fixtures'
import { cypher } from '../support/neo4j'

/**
 * The data lifecycle in the web interface (#33): the administration page that says which ontology
 * the graph is on, what the archive would take and each connector's retirement rules, and the
 * history of a node's property values on its page.
 *
 * The stack ships no migration to run, so a graph behind its build is made by moving the Ontology
 * node back a version, which is what a graph written by an older build looks like. Applying then
 * records the build's version, and the page says it is up to date. dan holds graph:admin in the
 * development realm, so he is offered the button.
 *
 * The archive is off in the compose stack, as it is everywhere unless an operator opts in, so the
 * page says so and offers no run. The history is made through the API: a repository stated, then
 * restated with another description, has one earlier version beside its current one.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

test.describe('data lifecycle', () => {
  // Both tests read or move the one Ontology node, so they take turns.
  test.describe.configure({ mode: 'serial' })

  test('the lifecycle page is reachable from the navigation', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('link', { name: 'Lifecycle', exact: true }).click()

    await expect(page).toHaveURL(/\/admin\/lifecycle$/)
    await expect(page.getByRole('heading', { name: 'Data lifecycle' })).toBeVisible()
    await expect(page.getByTestId('archive-status')).toContainText(/off/i)
    await expect(page.getByTestId('archive-run')).toHaveCount(0)
    await expect(page.getByTestId('connector-rules')).toContainText('github')
  })

  test('a graph behind its build shows Apply, and after applying is up to date', async ({
    page
  }) => {
    const rows = await cypher(
      'MATCH (o:Ontology) WITH o, o.version AS was SET o.version = $older RETURN was',
      { older: '1.3.0' }
    )
    expect(rows.length).toBe(1)

    await page.goto('/admin/lifecycle')
    await expect(page.getByTestId('migrations-status')).toContainText('1.3.0')
    await page.getByRole('button', { name: 'Apply' }).click()

    await expect(page.getByTestId('migrations-status')).toContainText(/up to date/i)
    await expect(page.getByRole('button', { name: 'Apply' })).toHaveCount(0)
  })

  test('a node page lists the versions of its properties', async ({ page, request }) => {
    const name = unique('history')
    const key = `github.com/acme/${name}`
    const url = `https://${key}`
    const props = { url, defaultBranch: 'main', topics: [], codeowners: [] }

    const created = await request.post('/api/v1/nodes/Repository', {
      data: { props: { ...props, description: 'first' } }
    })
    expect(created.status(), await created.text()).toBe(201)
    const updated = await request.put(`/api/v1/nodes/Repository/${key}`, {
      data: { props: { ...props, description: 'second' } }
    })
    expect(updated.status(), await updated.text()).toBe(200)

    await page.goto(`/nodes/Repository/${key}`)
    const history = page.getByTestId('node-history')
    await expect(history.getByTestId('history-entry')).toHaveCount(2)
    await expect(history.getByTestId('history-entry').first()).toContainText('second')
    await expect(history.getByTestId('history-entry').last()).toContainText('first')
  })
})
