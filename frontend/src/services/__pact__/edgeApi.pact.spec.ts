import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, edgeApi } from '../api'

const { like, eachLike } = MatchersV3

/**
 * The contract for the edge API the relationship panel uses.
 *
 * What matters here is the shape a panel has to render: an edge carries the name that *this* end
 * sees, so the same stored relationship reads as DEPENDS_ON from one node and DEPENDED_ON_BY from
 * the other.
 */
const TWO_REPOSITORIES = 'two repositories exist'
const A_DEPENDS_ON_EDGE = 'payments DEPENDS_ON shared-lib'

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
const PAYMENTS = 'Repository:github.com/acme/payments'
const SHARED = 'Repository:github.com/acme/shared-lib'

describe('edge API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('creates a typed relationship', async () => {
    provider
      .given(TWO_REPOSITORIES)
      .uponReceiving('a request to create a DEPENDS_ON edge')
      .withRequest({
        method: 'POST',
        path: '/api/v1/edges',
        headers: JSON_HEADERS,
        body: { type: 'DEPENDS_ON', fromId: PAYMENTS, toId: SHARED, props: { kind: 'library' } }
      })
      .willRespondWith({
        status: 201,
        headers: JSON_HEADERS,
        body: {
          type: like('DEPENDS_ON'),
          inverse: like('DEPENDED_ON_BY'),
          from: like({ id: PAYMENTS, type: 'Repository', key: 'github.com/acme/payments' }),
          to: like({ id: SHARED, type: 'Repository', key: 'github.com/acme/shared-lib' }),
          props: like({ kind: 'library' }),
          provenance: like({ sourceSystem: 'manual', confidence: 1.0, inferred: false })
        }
      })

    await provider.executeTest(async mockServer => {
      const created = await against(mockServer.url, () =>
        edgeApi.create({
          type: 'DEPENDS_ON',
          fromId: PAYMENTS,
          toId: SHARED,
          props: { kind: 'library' }
        })
      )

      expect(created.inverse).toBe('DEPENDED_ON_BY')
    })
  })

  it('refuses a pair the ontology does not allow, and says which are allowed', async () => {
    provider
      .given(TWO_REPOSITORIES)
      .uponReceiving('a request to create an edge between disallowed types')
      .withRequest({
        method: 'POST',
        path: '/api/v1/edges',
        headers: JSON_HEADERS,
        body: { type: 'OWNED_BY', fromId: 'Environment:production', toId: 'Team:platform' }
      })
      .willRespondWith({
        status: 400,
        headers: JSON_HEADERS,
        body: { error: 'edge not allowed', allowed: eachLike({ from: 'Repository', to: 'Team' }) }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        edgeApi
          .create({ type: 'OWNED_BY', fromId: 'Environment:production', toId: 'Team:platform' })
          .catch(error => error.response)
      )

      expect(failure.status).toBe(400)
      expect(failure.data.allowed[0].from).toBe('Repository')
    })
  })

  it('lists the edges of a node under the name that end sees', async () => {
    provider
      .given(A_DEPENDS_ON_EDGE)
      .uponReceiving('a request for the edges of shared-lib')
      .withRequest({
        method: 'GET',
        path: '/api/v1/nodes/Repository/github.com/acme/shared-lib/edges',
        query: { direction: 'both' }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          items: eachLike({
            type: 'DEPENDS_ON',
            inverse: 'DEPENDED_ON_BY',
            direction: 'in',
            displayName: 'DEPENDED_ON_BY',
            other: { id: PAYMENTS, type: 'Repository', key: 'github.com/acme/payments', props: {} },
            props: { kind: 'library' },
            provenance: { sourceSystem: 'manual', confidence: 1.0, inferred: false }
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const edges = await against(mockServer.url, () =>
        edgeApi.forNode('Repository', 'github.com/acme/shared-lib')
      )

      expect(edges[0].displayName).toBe('DEPENDED_ON_BY')
    })
  })

  it('removes an edge by its exact triple', async () => {
    provider
      .given(A_DEPENDS_ON_EDGE)
      .uponReceiving('a request to remove the DEPENDS_ON edge')
      .withRequest({
        method: 'DELETE',
        path: '/api/v1/edges',
        query: { type: 'DEPENDS_ON', fromId: PAYMENTS, toId: SHARED }
      })
      .willRespondWith({ status: 204 })

    await provider.executeTest(async mockServer => {
      await against(mockServer.url, () => edgeApi.remove('DEPENDS_ON', PAYMENTS, SHARED))
    })
  })
})
