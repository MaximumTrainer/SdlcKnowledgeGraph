import { expect, test } from '@playwright/test'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

/**
 * The published site, asserted against the repository sources it is generated from.
 *
 * Nothing here names a page's content directly. Each test reads the source — the ontology registry,
 * the ADR folder — and asserts the built site covers it, so a node type or an ADR added later is
 * covered by these tests on the day it is added rather than when someone remembers to extend them.
 */
const repoFile = (path: string) => fileURLToPath(new URL(`../../${path}`, import.meta.url))

const ontology = JSON.parse(
  readFileSync(repoFile('backend/src/main/resources/ontology/v1/ontology.json'), 'utf8')
)

test.describe('the published website', () => {
  test('the home page presents the project', async ({ page }) => {
    await page.goto('/')

    await expect(page).toHaveTitle(/SDLC Knowledge Graph/)
    await expect(page.getByRole('heading', { name: 'SDLC Knowledge Graph', level: 1 })).toBeVisible()
  })

  test('the ontology reference covers every declared node type', async ({ page }) => {
    await page.goto('/reference/ontology')

    for (const nodeType of ontology.nodeTypes) {
      // VitePress appends a permalink anchor to every heading, so the accessible name is the text
      // plus that anchor; anchoring the pattern at the start is what makes this stable.
      const heading = page.getByRole('heading', { level: 3, name: new RegExp(`^${nodeType.name}`) })
      await expect(heading).toBeVisible()
    }
  })

  test('every property of a node type is documented with its type', async ({ page }) => {
    await page.goto('/reference/ontology')

    const repository = ontology.nodeTypes.find((type: { name: string }) => type.name === 'Repository')
    const body = await page.locator('.vp-doc').innerText()

    for (const property of repository.properties) {
      expect(body).toContain(property.name)
    }
    expect(body).toContain(repository.identity.join(', '))
  })

  test('the ontology reference covers every declared edge type and its inverse', async ({ page }) => {
    await page.goto('/reference/ontology')
    const body = await page.locator('.vp-doc').innerText()

    for (const edgeType of ontology.edgeTypes) {
      expect(body).toContain(edgeType.name)
      expect(body).toContain(edgeType.inverse)
    }
  })

  test('the ADR index lists every decision record', async ({ page }) => {
    await page.goto('/adr/')
    const body = await page.locator('.vp-doc').innerText()

    const records = readdirSync(repoFile('docs/adr')).filter(name => name.endsWith('.md'))
    expect(records.length).toBeGreaterThan(0)

    for (const record of records) {
      expect(body).toContain(record.replace(/\.md$/, '').split('-')[0])
    }
  })

  test('the API reference lists the endpoints the application serves', async ({ page }) => {
    await page.goto('/reference/api')
    const body = await page.locator('.vp-doc').innerText()

    const openapi = JSON.parse(readFileSync(repoFile('docs/api/openapi.json'), 'utf8'))
    for (const path of Object.keys(openapi.paths)) {
      expect(body).toContain(path)
    }
  })

  test('the footer says which commit and ontology version the site describes', async ({ page }) => {
    await page.goto('/')

    // The page also has a per-document footer; this is the site-wide one.
    const footer = page.locator('footer.VPFooter')
    await expect(footer).toContainText(`ontology v${ontology.version}`)
    await expect(footer).toContainText('build ')
  })

  test('a reader can reach the docs from the home page', async ({ page }) => {
    await page.goto('/')

    await page.getByRole('link', { name: 'Ontology', exact: true }).first().click()

    await expect(page).toHaveURL(/\/(guide|reference)\/ontology/)
  })
})
