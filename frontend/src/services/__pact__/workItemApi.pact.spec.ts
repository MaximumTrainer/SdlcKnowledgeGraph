import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, lineageApi, nodeApi } from '../api'

const { like, eachLike, regex } = MatchersV3

/**
 * The contract for change lineage (#85): where a work item is live, and what intent a deployment
 * carries.
 *
 * A work item's identity is its URI and a deployment's key holds `/` and `#`, so both travel as
 * query parameters, and an ExternalWorkItem is read by key rather than by a path it cannot travel
 * in. What the consumer relies on is the shape: each deployment with the environment it is in and
 * the changes that carry the work item there, and `lineage` saying `unknown` - not an empty list
 * meaning "nothing" - for a deployment whose artifact has no CONTAINS edge.
 */
const LIVE = 'work item chorus://task/01JABC is live in production'
const NO_LINEAGE = 'a deployment whose artifact contains no changes'
const EMPTY = 'no work items exist'

const WORK_ITEM_URI = 'chorus://task/01JABC'
const WORK_ITEM_ID = `ExternalWorkItem:${WORK_ITEM_URI}`
const CHANGE_ID = 'Change:github.com/acme/payments@a1b2c3'
const DEPLOYMENT_ID = 'Deployment:acme/payments:1.4.0#production#1789297200'
const UNTRACED_DEPLOYMENT_ID = 'Deployment:acme/payments:1.3.0#production#1789210800'
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/
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

const environment = { id: like('Environment:production'), key: 'production', tier: like('other') }

describe('change lineage API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('answers where a work item is live', async () => {
    provider
      .given(LIVE)
      .uponReceiving('a request for the deployments carrying chorus://task/01JABC')
      .withRequest({
        method: 'GET',
        path: '/api/v1/work-items/deployments',
        query: { uri: WORK_ITEM_URI }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          workItem: { id: WORK_ITEM_ID, uri: WORK_ITEM_URI, system: 'chorus' },
          deployments: eachLike({
            id: like(DEPLOYMENT_ID),
            deployedAt: regex(INSTANT, '2026-09-13T11:00:00Z'),
            status: like('SUCCESS'),
            environment,
            artifacts: eachLike({ id: like('Artifact:acme/payments:1.4.0') }),
            changes: eachLike({
              id: like(CHANGE_ID),
              sha: like('a1b2c3'),
              repositoryKey: like('github.com/acme/payments')
            })
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const live = await against(mockServer.url, () =>
        lineageApi.deploymentsOfWorkItem(WORK_ITEM_URI)
      )

      expect(live.workItem.uri).toBe(WORK_ITEM_URI)
      expect(live.deployments[0].environment?.key).toBe('production')
      expect(live.deployments[0].changes[0].sha).toBe('a1b2c3')
    })
  })

  it('answers the work items a deployment carries', async () => {
    provider
      .given(LIVE)
      .uponReceiving('a request for the work items the production deployment carries')
      .withRequest({
        method: 'GET',
        path: '/api/v1/deployments/work-items',
        query: { deploymentId: DEPLOYMENT_ID }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          deployment: { id: DEPLOYMENT_ID, environment },
          lineage: 'known',
          changes: eachLike({
            id: like(CHANGE_ID),
            sha: like('a1b2c3'),
            artifact: like('Artifact:acme/payments:1.4.0')
          }),
          workItems: eachLike({
            id: like(WORK_ITEM_ID),
            uri: like(WORK_ITEM_URI),
            system: like('chorus'),
            changes: eachLike(CHANGE_ID)
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const carried = await against(mockServer.url, () =>
        lineageApi.workItemsOfDeployment(DEPLOYMENT_ID)
      )

      expect(carried.lineage).toBe('known')
      expect(carried.workItems[0].uri).toBe(WORK_ITEM_URI)
    })
  })

  it('says a deployment with no change lineage is unknown, not empty', async () => {
    provider
      .given(NO_LINEAGE)
      .uponReceiving('a request for the work items of a deployment with no change lineage')
      .withRequest({
        method: 'GET',
        path: '/api/v1/deployments/work-items',
        query: { deploymentId: UNTRACED_DEPLOYMENT_ID }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          deployment: { id: UNTRACED_DEPLOYMENT_ID },
          lineage: 'unknown',
          changes: [],
          workItems: []
        }
      })

    await provider.executeTest(async mockServer => {
      const carried = await against(mockServer.url, () =>
        lineageApi.workItemsOfDeployment(UNTRACED_DEPLOYMENT_ID)
      )

      expect(carried.lineage).toBe('unknown')
      expect(carried.workItems).toEqual([])
    })
  })

  it('refuses a work item the graph does not hold, naming it', async () => {
    provider
      .given(EMPTY)
      .uponReceiving('a request for the deployments of a work item that does not exist')
      .withRequest({
        method: 'GET',
        path: '/api/v1/work-items/deployments',
        query: { uri: 'chorus://task/nothing' }
      })
      .willRespondWith({
        status: 404,
        headers: JSON_HEADERS,
        body: { error: 'node not found', missing: ['ExternalWorkItem:chorus://task/nothing'] }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        lineageApi.deploymentsOfWorkItem('chorus://task/nothing').catch(error => error.response)
      )

      expect(failure.status).toBe(404)
      expect(failure.data.missing).toEqual(['ExternalWorkItem:chorus://task/nothing'])
    })
  })

  it('reads a work item by its URI key, which no path segment can carry', async () => {
    provider
      .given(LIVE)
      .uponReceiving('a request for the ExternalWorkItem chorus://task/01JABC by key')
      .withRequest({
        method: 'GET',
        path: '/api/v1/nodes/ExternalWorkItem/by-key',
        query: { key: WORK_ITEM_URI }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: WORK_ITEM_ID,
          type: 'ExternalWorkItem',
          key: WORK_ITEM_URI,
          props: like({ uri: WORK_ITEM_URI, system: 'chorus' }),
          provenance: like({ sourceSystem: 'manual', confidence: 1.0, inferred: false })
        }
      })

    await provider.executeTest(async mockServer => {
      const workItem = await against(mockServer.url, () =>
        nodeApi.get('ExternalWorkItem', WORK_ITEM_URI)
      )

      expect(workItem.key).toBe(WORK_ITEM_URI)
    })
  })
})
