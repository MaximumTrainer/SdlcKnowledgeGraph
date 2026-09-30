import { expect, test, type APIResponse } from '@playwright/test'

/**
 * The deployment contract (docs/DEPLOYMENT.md). Each test is named for the requirement it proves.
 *
 * D7, D8, D10 and D12 are not here: they cannot be observed by a client, so the workflow that makes
 * the deployment asserts them instead. docs/DEPLOYMENT.md says which is which.
 */

const WRITE_VERBS = ['POST', 'PUT', 'PATCH', 'DELETE'] as const
const WRITE_PATHS = [
  '/api/v1/nodes/Team',
  '/api/v1/nodes/Team/platform',
  '/api/v1/edges',
  '/api/v1/repositories',
  // An endpoint nobody has built: the refusal is deny-by-default, not a list of known writes.
  '/api/v1/a-write-endpoint-nobody-has-built-yet'
]

const readOnlyRefusal = async (response: APIResponse) => {
  expect(response.status(), `${response.url()} answered ${response.status()}`).toBe(403)
  expect(await response.json()).toEqual({ error: 'this instance is read-only' })
}

const deployment = async (request: import('@playwright/test').APIRequestContext) => {
  const response = await request.get('/actuator/info')
  expect(response.status()).toBe(200)
  return (await response.json()).deployment
}

test('D1 the liveness and readiness probes answer 200 UP', async ({ request }) => {
  for (const probe of ['/actuator/health/liveness', '/actuator/health/readiness']) {
    const response = await request.get(probe)
    expect(response.status(), probe).toBe(200)
    expect((await response.json()).status, probe).toBe('UP')
  }
})

test('D2 /actuator/info says what the deployment is running', async ({ request }) => {
  const info = await deployment(request)
  const ontology = await (await request.get('/api/v1/ontology')).json()

  expect(info.commit).toMatch(/^[0-9a-f]{40}$/)
  expect(info.version).toEqual(expect.any(String))
  expect(info.version).not.toBe('unknown')
  expect(info.ontologyVersion).toBe(ontology.version)
  expect(info.profile).toEqual(expect.any(String))
  expect(info.readOnly).toEqual(expect.any(Boolean))
  expect(['oidc', 'anonymous-read-only']).toContain(info.authentication)
})

test('D3 the deployment is running the commit that was meant to be deployed', async ({ request }) => {
  const expected = process.env.EXPECTED_COMMIT
  test.skip(!expected, 'EXPECTED_COMMIT is not set, so there is nothing to compare against')

  expect((await deployment(request)).commit).toBe(expected)
})

test('D4 a deployment without authentication runs read-only', async ({ request }) => {
  // A deployment either trusts an identity provider (oidc) or serves reads to anyone, and then it
  // must accept no writes (#118). The API refuses to start in any other state (D13); this checks
  // what the running deployment says about itself.
  const info = await deployment(request)
  expect(['oidc', 'anonymous-read-only']).toContain(info.authentication)
  if (info.authentication !== 'oidc') expect(info.readOnly).toBe(true)
})

for (const verb of WRITE_VERBS) {
  test(`D5 a ${verb} under /api/v1 is refused`, async ({ request }) => {
    for (const path of WRITE_PATHS) {
      // A well-formed body, so a write that got past the refusal would succeed rather than fail
      // validation and make a missing refusal look like one.
      await readOnlyRefusal(await request.fetch(path, { method: verb, data: { props: { name: 'conformance' } } }))
    }
  })
}

test('D5 a GraphQL mutation is refused', async ({ request }) => {
  const response = await request.post('/graphql', {
    data: { query: 'mutation { deleteRepository(id: "conformance") }' }
  })
  // A deployment may not publish GraphQL at all, as the web interface does not: then the mutation
  // never reaches the API, which conforms. What must never come back is GraphQL's own answer.
  if (response.status() === 403) {
    expect(await response.json()).toEqual({ error: 'this instance is read-only' })
  } else {
    expect([404, 405], `/graphql answered ${response.status()}`).toContain(response.status())
    expect(await response.text()).not.toMatch(/"data"\s*:|"errors"\s*:/)
  }
})

test('D6 the ingest endpoints still require their token', async ({ request }) => {
  const bodies: Record<string, object> = {
    '/api/v1/ingest/deployment': { repository: 'github.com/example/conformance', artifacts: [] },
    '/api/v1/ingest/seed': { nodes: [] },
  }
  const attempts: Record<string, string>[] = [{}, { Authorization: 'Bearer not-the-token' }]
  for (const [path, data] of Object.entries(bodies)) {
    for (const headers of attempts) {
      const response = await request.post(path, { data, headers })
      // 401 when the deployment has a token configured; 503 when it has none, which disables the
      // endpoint. Never 403: the read-only refusal must not be what stands in front of it.
      expect([401, 503], `${path} ${JSON.stringify(headers)}`).toContain(response.status())
    }
  }
})

test('D9 the metrics and the rest of the actuator are not public', async ({ request }) => {
  for (const path of ['/actuator/prometheus', '/actuator/metrics', '/actuator/env', '/actuator/configprops']) {
    const response = await request.get(path)
    // The web interface answers an unknown path with its own index page, so the test is whether the
    // actuator's answer came back, not the status code.
    const body = await response.text()
    expect(body, path).not.toMatch(/jvm_memory|"names"\s*:|"propertySources"|"contexts"\s*:/)
  }
})

test('D11 an error response gives nothing away about the internals', async ({ request }) => {
  const failures = [
    await request.get('/api/v1/nodes/NoSuchType'),
    await request.get('/api/v1/nodes/Repository/%E0%A4%A'),
    await request.get('/api/v1/edges?direction=sideways'),
    await request.post('/graphql', { data: { query: '{ noSuchField' } }),
    await request.post('/graphql', { data: { query: '{ repositories(filter: { nope: 1 }) { id } }' } })
  ]
  for (const response of failures) {
    const body = await response.text()
    expect(body, response.url()).not.toMatch(/\bat [a-z]+(\.[a-zA-Z0-9_$]+)+\(|Exception:|\tat /)
    expect(body, response.url()).not.toContain('MATCH (')
    expect(body, response.url()).not.toMatch(/bolt:\/\/|neo4j:7687|NEO4J_PASSWORD|\.internal:/)
  }
})
