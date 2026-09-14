import { expect, test } from '@playwright/test'

/**
 * Registering a repository by its git remote, through the browser.
 *
 * The unit tests prove the parser and the acceptance tests prove the API. What is only provable here
 * is the part a person actually meets: that the key is shown before saving, that the node lands under
 * that key, and that the same repository written another way is refused rather than duplicated (#8).
 */
const unique = () => `payments-${Date.now()}-${Math.floor(Math.random() * 1000)}`

test.describe('repository identity from its git remote', () => {
  test('the key is previewed before saving, and is the key the node lands under', async ({ page }) => {
    const name = unique()
    await page.goto('/nodes/Repository/new')

    // The scp-style remote, which is what `git remote -v` prints and what someone is most likely to
    // paste. Nothing about it looks like the key it becomes.
    await page.getByLabel('url').fill(`git@github.com:Acme/${name}.git`)

    await expect(page.getByTestId('remote-key')).toContainText(`github.com/acme/${name.toLowerCase()}`)

    await page.getByLabel('defaultBranch').fill('main')
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page).toHaveURL(new RegExp(`/nodes/Repository/github.com/acme/${name.toLowerCase()}$`))
  })

  test('the detail page links out to the repository, named after its host', async ({ page }) => {
    const name = unique()
    await page.goto('/nodes/Repository/new')
    await page.getByLabel('url').fill(`https://github.com/acme/${name}`)
    await page.getByLabel('defaultBranch').fill('main')
    await page.getByRole('button', { name: 'Save' }).click()

    const link = page.getByTestId('open-remote')
    await expect(link).toHaveText('Open in github.com')
    await expect(link).toHaveAttribute('href', `https://github.com/acme/${name}`)
  })

  test('something that is not a git remote is refused before it is saved', async ({ page }) => {
    await page.goto('/nodes/Repository/new')

    await page.getByLabel('url').fill('https://example.com/page')

    await expect(page.getByTestId('remote-key')).toContainText('Not a git remote')
  })

  test('the same repository written another way is not registered twice', async ({ page }) => {
    const name = unique()
    await page.goto('/nodes/Repository/new')
    await page.getByLabel('url').fill(`https://github.com/acme/${name}`)
    await page.getByLabel('defaultBranch').fill('main')
    await page.getByRole('button', { name: 'Save' }).click()
    await expect(page).toHaveURL(new RegExp(`/nodes/Repository/github.com/acme/${name}$`))

    // The same remote, in the notation git writes rather than the one the browser shows.
    await page.goto('/nodes/Repository/new')
    await page.getByLabel('url').fill(`git@github.com:Acme/${name}.git`)
    await page.getByLabel('defaultBranch').fill('main')
    await page.getByRole('button', { name: 'Save' }).click()

    await expect(page.getByTestId('form-error')).toContainText(/already|exists/i)
  })
})
