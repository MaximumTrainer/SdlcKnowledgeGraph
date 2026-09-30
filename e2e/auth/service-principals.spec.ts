import { expect, test, type Page } from '@playwright/test'

/**
 * Connectors and agents are principals of their own (#115): a client of the development realm gets
 * a token with the client-credentials grant, and once a user has registered it as a service
 * principal, what it writes names it, says it is a service and names the team it acts for.
 *
 * The realm (backend/src/acceptanceTest/resources/keycloak/sdlc-realm.json) ships github-connector
 * and triage-agent as examples; their secrets are development values that exist nowhere else.
 */

const TOKEN_ENDPOINT = 'http://localhost:8081/realms/sdlc/protocol/openid-connect/token'

/** A team name no other run will produce, so the node is always a new one. */
const uniqueTeam = () => `written-by-connector-${Date.now()}-${Math.floor(Math.random() * 1000)}`

/** Signs dan in through the web interface and hands back the access token it holds. */
async function signInAsDan(page: Page): Promise<string> {
  await page.goto('/')
  await expect(page).toHaveURL(/\/realms\/sdlc\/protocol\/openid-connect\/auth/)
  await page.getByLabel('Username').fill('dan')
  await page.getByLabel('Password', { exact: true }).fill('dan')
  await page.getByRole('button', { name: 'Sign In' }).click()
  await expect(page.getByTestId('signed-in-user')).toHaveText('dan')
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(name => name.startsWith('oidc.user:'))
    return key ? (JSON.parse(sessionStorage.getItem(key) ?? '{}').access_token as string) : ''
  })
}

test('an unregistered client is refused with 403', async ({ request }) => {
  const token = await request.post(TOKEN_ENDPOINT, {
    form: {
      grant_type: 'client_credentials',
      client_id: 'rogue-agent',
      client_secret: 'rogue-agent-dev-only'
    }
  })
  expect(token.ok()).toBeTruthy()
  const { access_token } = await token.json()

  const response = await request.get('/api/v1/nodes/Repository', {
    headers: { Authorization: `Bearer ${access_token}` }
  })

  expect(response.status()).toBe(403)
  expect(await response.json()).toMatchObject({
    error: 'unregistered service principal',
    clientId: 'rogue-agent'
  })
})

test('a registered connector writes as itself, and the web interface says for which team', async ({
  page,
  request
}) => {
  const user = { Authorization: `Bearer ${await signInAsDan(page)}` }

  // Both may exist from an earlier run against the same stack; a 409 says so and is fine.
  const team = await request.post('/api/v1/nodes/Team', {
    headers: user,
    data: { props: { name: 'team-platform' } }
  })
  expect([201, 409]).toContain(team.status())
  const registered = await request.post('/api/v1/service-principals', {
    headers: user,
    data: {
      name: 'github-connector',
      ownedBy: 'team-platform',
      description: 'The GitHub connector'
    }
  })
  expect([201, 409]).toContain(registered.status())

  const token = await request.post(TOKEN_ENDPOINT, {
    form: {
      grant_type: 'client_credentials',
      client_id: 'github-connector',
      client_secret: 'github-connector-dev-only'
    }
  })
  const { access_token } = await token.json()
  const created = await request.post('/api/v1/nodes/Team', {
    headers: { Authorization: `Bearer ${access_token}` },
    data: { props: { name: uniqueTeam() } }
  })
  expect(created.status()).toBe(201)
  const node = await created.json()
  expect(node.provenance).toMatchObject({
    writtenBy: 'github-connector',
    principalType: 'service',
    onBehalfOfTeam: 'team-platform'
  })

  await page.goto(`/nodes/Team/${node.key}`)
  await expect(page.getByTestId('provenance-written-by')).toHaveText('github-connector')
  await expect(page.getByTestId('provenance-principal-type')).toHaveText('service')
  await expect(page.getByTestId('provenance-on-behalf-of-team')).toHaveText('team-platform')
})
