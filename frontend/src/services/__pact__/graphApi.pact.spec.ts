import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, graphApi } from '../api'

const { like, eachLike, boolean, decimal, integer } = MatchersV3

/**
 * The contract for the graph view's neighbourhood (#9, FR1 to FR3).
 *
 * What the canvas relies on is the shape: every node with a label to draw and the provenance its
 * drawer shows, every edge with the id the view merges expansions by, and the confidence and inferred
 * flag that decide how it is drawn. `truncated` is what raises the banner, so it is held to a boolean
 * both ways. Which nodes a neighbourhood holds is the provider's business, and the acceptance suite's.
 */
const NEIGHBOURHOOD = 'payments has a neighbourhood with an inferred edge'
const HUB = 'a hub repository depends on more than 500 others'

const PAYMENTS = 'Repository:github.com/acme/payments'
const HUB_ID = 'Repository:github.com/acme/hub'
const JSON_HEADERS = { 'Content-Type': 'application/json' }

const provider = new PactV3({
  consumer: 'sdlc-graph-frontend',
  provider: 'sdlc-graph-backend',
  dir: '../contracts/pacts'
})

const against = async <T>(mockServerUrl: string, call: () => Promise<T>): Promise<T> => {
  apiClient.defaults.baseURL = `${mockServerUrl}/api/v1`
  return call()
}

const node = {
  id: like(PAYMENTS),
  type: like('Repository'),
  key: like('github.com/acme/payments'),
  label: like('payments'),
  distance: integer(0),
  props: like({ name: 'payments' }),
  provenance: like({ sourceSystem: 'manual', confidence: 1.0, inferred: false })
}

const edge = {
  id: like(`DEPENDS_ON:${PAYMENTS}>Repository:github.com/acme/shared-lib`),
  type: like('DEPENDS_ON'),
  inverse: like('DEPENDED_ON_BY'),
  from: like(PAYMENTS),
  to: like('Repository:github.com/acme/shared-lib'),
  confidence: decimal(1.0),
  inferred: boolean(false)
}

describe('graph API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('answers the neighbourhood of a node, ready to draw', async () => {
    provider
      .given(NEIGHBOURHOOD)
      .uponReceiving('a request for the depth-1 neighbourhood of payments')
      .withRequest({
        method: 'GET',
        path: '/api/v1/graph/neighbourhood',
        query: { nodeId: PAYMENTS, depth: '1', direction: 'both' }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          root: PAYMENTS,
          truncated: false,
          nodes: eachLike(node),
          edges: eachLike(edge)
        }
      })

    await provider.executeTest(async mockServer => {
      const subgraph = await against(mockServer.url, () =>
        graphApi.neighbourhood({ nodeId: PAYMENTS })
      )

      expect(subgraph.root).toBe(PAYMENTS)
      expect(subgraph.truncated).toBe(false)
      expect(subgraph.nodes[0].label).toBe('payments')
      expect(subgraph.edges[0].id).toContain('>')
    })
  })

  it('says when the cap cut the neighbourhood short', async () => {
    provider
      .given(HUB)
      .uponReceiving('a request for the neighbourhood of a hub with more than 500 neighbours')
      .withRequest({
        method: 'GET',
        path: '/api/v1/graph/neighbourhood',
        query: { nodeId: HUB_ID, depth: '1', direction: 'both' }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          root: HUB_ID,
          truncated: true,
          nodes: eachLike(node),
          edges: eachLike(edge)
        }
      })

    await provider.executeTest(async mockServer => {
      const subgraph = await against(mockServer.url, () =>
        graphApi.neighbourhood({ nodeId: HUB_ID })
      )

      expect(subgraph.truncated).toBe(true)
    })
  })

  it('refuses a depth outside 1 to 3, naming the field', async () => {
    provider
      .given(NEIGHBOURHOOD)
      .uponReceiving('a request for the neighbourhood of payments at depth 4')
      .withRequest({
        method: 'GET',
        path: '/api/v1/graph/neighbourhood',
        query: { nodeId: PAYMENTS, depth: '4', direction: 'both' }
      })
      .willRespondWith({
        status: 400,
        headers: JSON_HEADERS,
        body: { error: like('depth must be between 1 and 3, was 4'), field: 'depth' }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        graphApi.neighbourhood({ nodeId: PAYMENTS, depth: 4 }).catch(error => error.response)
      )

      expect(failure.status).toBe(400)
      expect(failure.data.field).toBe('depth')
    })
  })
})
