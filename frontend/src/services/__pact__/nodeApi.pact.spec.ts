import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, nodeApi } from '../api'

const { like, eachLike, integer } = MatchersV3

/**
 * The contract for the generic node API the editing screens use.
 *
 * The screens are rendered from the ontology, so what matters here is not any one node type but the
 * envelope every type shares: props plus derived identity plus provenance, and the four refusals a
 * form has to be able to show a user.
 */
const NO_TEAMS = 'no Team nodes exist'
const TEAM_PLATFORM_EXISTS = 'a Team named platform exists'

const provider = new PactV3({
  consumer: 'sdlc-graph-frontend',
  provider: 'sdlc-graph-backend',
  dir: '../contracts/pacts'
})

const against = async <T>(mockServerUrl: string, call: () => Promise<T>): Promise<T> => {
  apiClient.defaults.baseURL = `${mockServerUrl}/api/v1`
  return call()
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

describe('node API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('creates a node', async () => {
    provider
      .given(NO_TEAMS)
      .uponReceiving('a request to create a Team')
      .withRequest({
        method: 'POST',
        path: '/api/v1/nodes/Team',
        headers: JSON_HEADERS,
        body: { props: { name: 'platform' } }
      })
      .willRespondWith({
        status: 201,
        headers: JSON_HEADERS,
        body: {
          id: like('Team:platform'),
          type: like('Team'),
          key: like('platform'),
          props: like({ name: 'platform' }),
          provenance: like({ sourceSystem: 'manual', confidence: 1.0, inferred: false })
        }
      })

    await provider.executeTest(async mockServer => {
      const created = await against(mockServer.url, () =>
        nodeApi.create('Team', { name: 'platform' })
      )

      expect(created.key).toBe('platform')
      expect(created.provenance.sourceSystem).toBe('manual')
    })
  })

  it('updates a node in place', async () => {
    provider
      .given(TEAM_PLATFORM_EXISTS)
      .uponReceiving('a request to update the platform Team')
      .withRequest({
        method: 'PUT',
        path: '/api/v1/nodes/Team/platform',
        headers: JSON_HEADERS,
        body: { props: { name: 'platform', email: 'platform@acme.example' } }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: like('Team:platform'),
          type: like('Team'),
          key: like('platform'),
          props: like({ name: 'platform', email: 'platform@acme.example' }),
          provenance: like({ sourceSystem: 'manual', confidence: 1.0, inferred: false })
        }
      })

    await provider.executeTest(async mockServer => {
      const updated = await against(mockServer.url, () =>
        nodeApi.update('Team', 'platform', { name: 'platform', email: 'platform@acme.example' })
      )

      expect(updated.key).toBe('platform')
    })
  })

  it('reports an identity-key collision with the id that already holds it', async () => {
    provider
      .given(TEAM_PLATFORM_EXISTS)
      .uponReceiving('a request to create a Team that already exists')
      .withRequest({
        method: 'POST',
        path: '/api/v1/nodes/Team',
        headers: JSON_HEADERS,
        body: { props: { name: 'platform' } }
      })
      .willRespondWith({
        status: 409,
        headers: JSON_HEADERS,
        body: { error: 'node exists', existingId: like('Team:platform') }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        nodeApi.create('Team', { name: 'platform' }).catch(error => error.response)
      )

      expect(failure.status).toBe(409)
      expect(failure.data.error).toBe('node exists')
      expect(failure.data.existingId).toBeTruthy()
    })
  })

  it('reports a missing required property per field', async () => {
    provider
      .given(NO_TEAMS)
      .uponReceiving('a request to create a Team with no name')
      .withRequest({
        method: 'POST',
        path: '/api/v1/nodes/Team',
        headers: JSON_HEADERS,
        body: { props: { email: 'platform@acme.example' } }
      })
      .willRespondWith({
        status: 400,
        headers: JSON_HEADERS,
        body: { errors: eachLike({ field: 'name', message: 'name is required' }) }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        nodeApi.create('Team', { email: 'platform@acme.example' }).catch(error => error.response)
      )

      expect(failure.status).toBe(400)
      expect(failure.data.errors[0].field).toBe('name')
    })
  })

  it('refuses to delete a node that still has edges', async () => {
    provider
      .given(TEAM_PLATFORM_EXISTS)
      .uponReceiving('a request to delete a Team that still has edges')
      .withRequest({ method: 'DELETE', path: '/api/v1/nodes/Team/platform' })
      .willRespondWith({
        status: 409,
        headers: JSON_HEADERS,
        body: { error: 'node has edges', edgeCount: integer(1) }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        nodeApi.remove('Team', 'platform').catch(error => error.response)
      )

      expect(failure.status).toBe(409)
      expect(failure.data.edgeCount).toBeGreaterThan(0)
    })
  })

  it('deletes a node when cascade is asked for', async () => {
    provider
      .given(TEAM_PLATFORM_EXISTS)
      .uponReceiving('a request to delete a Team with cascade')
      .withRequest({
        method: 'DELETE',
        path: '/api/v1/nodes/Team/platform',
        query: { cascade: 'true' }
      })
      .willRespondWith({ status: 204 })

    await provider.executeTest(async mockServer => {
      await against(mockServer.url, () => nodeApi.remove('Team', 'platform', { cascade: true }))
    })
  })

  it('lists nodes of a type', async () => {
    provider
      .given(TEAM_PLATFORM_EXISTS)
      .uponReceiving('a request for all Team nodes')
      .withRequest({ method: 'GET', path: '/api/v1/nodes/Team' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          items: eachLike({
            id: 'Team:platform',
            type: 'Team',
            key: 'platform',
            props: { name: 'platform' }
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const page = await against(mockServer.url, () => nodeApi.list('Team'))

      expect(page.items[0].key).toBe('platform')
    })
  })
})
