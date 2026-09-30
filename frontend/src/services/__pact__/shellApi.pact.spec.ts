import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { actuatorClient, apiClient, infoApi, ontologyApi } from '../api'

const { like, eachLike, regex } = MatchersV3

/**
 * The contract for what the application shell reads before it can draw anything: the node types to
 * offer in the navigation, which of them describe the graph itself, and which build is serving.
 */
const ANY_GRAPH = 'no repositories exist'

const provider = new PactV3({
  consumer: 'sdlc-graph-frontend',
  provider: 'sdlc-graph-backend',
  dir: '../contracts/pacts'
})

describe('application shell contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
    actuatorClient.defaults.baseURL = '/actuator'
  })

  it('reads the node types, marked meta or not, each property described', async () => {
    // The editor shows a property's description as help (#81), so every property of every type
    // must carry one. Its examples are not pinned here: they are of the property's own type, which
    // differs from property to property, and a Pact type matcher would hold them all to the first.
    provider
      .given(ANY_GRAPH)
      .uponReceiving('a request for the ontology')
      .withRequest({ method: 'GET', path: '/api/v1/ontology' })
      .willRespondWith({
        status: 200,
        body: {
          version: like('1.0.0'),
          nodeTypes: eachLike({
            name: like('Repository'),
            meta: like(false),
            properties: eachLike({
              name: like('url'),
              type: like('string'),
              required: like(true),
              description: like('The git remote, stored canonicalised as https://host/org/name')
            })
          }),
          edgeTypes: eachLike({ name: like('DEPENDS_ON') })
        }
      })

    await provider.executeTest(async mockServer => {
      apiClient.defaults.baseURL = `${mockServer.url}/api/v1`
      const ontology = await ontologyApi.get()
      expect(ontology.nodeTypes[0]).toMatchObject({ name: 'Repository', meta: false })
      expect(ontology.nodeTypes[0].properties[0].description).toContain('git remote')
    })
  })

  it('reads which build and ontology are serving', async () => {
    provider
      .given(ANY_GRAPH)
      .uponReceiving('a request for the deployment info')
      // Asked for as plain JSON: left to choose, the actuator answers with its own media type.
      .withRequest({
        method: 'GET',
        path: '/actuator/info',
        headers: { Accept: 'application/json' }
      })
      .willRespondWith({
        status: 200,
        headers: { 'Content-Type': regex(/^application\/json.*/, 'application/json') },
        body: {
          deployment: {
            commit: regex(/^([0-9a-f]{40}|unknown)$/, '0123456789abcdef0123456789abcdef01234567'),
            version: like('0.0.1-SNAPSHOT'),
            ontologyVersion: like('1.0.0'),
            readOnly: like(false)
          }
        }
      })

    await provider.executeTest(async mockServer => {
      actuatorClient.defaults.baseURL = `${mockServer.url}/actuator`
      const info = await infoApi.get()
      expect(info.deployment.ontologyVersion).toBe('1.0.0')
    })
  })
})
