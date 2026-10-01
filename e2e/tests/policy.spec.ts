import { expect, test, type Page } from '@playwright/test'
import { accessTokenOf, freshSession, signIn } from '../support/session'

/**
 * One authorisation policy decides every request (#30, #95). The development realm's "viewer"
 * (password "viewer") holds graph:read and graph:write, as dan does, but carries the viewer role in
 * its `sdlc_roles` claim: the policy lets them read and nothing more, and keeps from them what is
 * above internal. dan's token carries no roles, so the policy judges him by his scopes alone, as
 * every user was judged before roles existed.
 *
 * Each test signs its own user in, so it uses Playwright's own `test` rather than the suite's
 * fixture, which would start it signed in as dan.
 */
const unique = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`

const explainUpdate = async (page: Page) => {
  await page.goto('/admin/policy')
  await expect(page.getByTestId('policy-revision')).toHaveText('sdlc-authz-1.0.0')
  await page.getByTestId('explain-action').selectOption('update')
  await page.getByTestId('explain-type').selectOption('Repository')
  await page.getByTestId('explain-submit').click()
}

test('the policy page explains a viewer may not change the graph, naming the rule', async ({
  page
}) => {
  await signIn(page, 'viewer', '/nodes/Repository')

  await explainUpdate(page)

  await expect(page.getByTestId('explain-verdict')).toContainText('Denied')
  await expect(page.getByTestId('explain-verdict')).toContainText('roles')
  await expect(page.getByTestId('explain-reason')).toHaveText(
    'the role viewer may not update Repository'
  )
  await expect(page.getByTestId('explain-clearance')).toHaveText('internal')
})

test('the policy page explains that a user without roles is judged by their scopes', async ({
  page
}) => {
  await signIn(page, 'dan', '/nodes/Repository')

  await explainUpdate(page)

  await expect(page.getByTestId('explain-verdict')).toContainText('Allowed')
  await expect(page.getByTestId('explain-result')).toContainText('judged by the token')
})

test('a write the policy refuses is explained in a banner, naming the rule and why', async ({
  page
}) => {
  await signIn(page, 'viewer', '/nodes/Team/new')

  await page.getByLabel('name').fill(unique('viewer-team'))
  await page.getByRole('button', { name: 'Save' }).click()

  await expect(page.getByTestId('access-denied')).toBeVisible()
  await expect(page.getByTestId('access-denied-policy')).toHaveText('roles')
  await expect(page.getByTestId('access-denied-reason')).toHaveText(
    'the role viewer may not create Team'
  )
  await expect(page).toHaveURL(/\/nodes\/Team\/new$/)
})

test('the API refuses a viewer with the policy that refused and why, and redacts what is above them', async ({
  page,
  request
}) => {
  const viewer = await (async () => {
    await signIn(page, 'viewer')
    return accessTokenOf(page)
  })()
  const dan = String((await freshSession()).user.access_token)
  const name = unique('policy-team')
  const created = await request.post('/api/v1/nodes/Team', {
    headers: { Authorization: `Bearer ${dan}` },
    data: { props: { name, email: 'policy@acme.example' } }
  })
  expect(created.status()).toBe(201)

  const refused = await request.put(`/api/v1/nodes/Team/${name}`, {
    headers: { Authorization: `Bearer ${viewer}` },
    data: { props: { name, email: 'other@acme.example' } }
  })
  expect(refused.status()).toBe(403)
  expect(await refused.json()).toEqual({
    error: 'policy denied',
    policy: 'roles',
    reason: 'the role viewer may not update Team'
  })

  const asViewer = await request.get(`/api/v1/nodes/Team/${name}`, {
    headers: { Authorization: `Bearer ${viewer}` }
  })
  const asDan = await request.get(`/api/v1/nodes/Team/${name}`, {
    headers: { Authorization: `Bearer ${dan}` }
  })
  const seenByViewer = await asViewer.json()
  expect(seenByViewer.props.email).toBeUndefined()
  expect(seenByViewer.redacted).toEqual(['email'])
  expect((await asDan.json()).props.email).toBe('policy@acme.example')
})
