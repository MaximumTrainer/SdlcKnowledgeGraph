import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient } from '../api'
import { connectorApi } from '../connectorApi'
import { syncRunApi } from '../syncRunApi'

const { like, eachLike, integer, boolean, regex } = MatchersV3

/**
 * The contract for the sync run history and connector freshness (#29, FR8).
 *
 * What the screens rely on is the envelope: a page with its totals, a run with its counts and its
 * error, a run in full with `details`, and a connector's freshness block. Which runs exist is the
 * provider's business, so the list is matched by shape rather than by content.
 */
const FAILED_RUN_EXISTS = 'a FAILED sync run of github exists'
const EMPTY_GRAPH = 'no repositories exist'
const RUN_ID = 'pact-run-1'

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

const summary = {
  id: like(RUN_ID),
  connector: like('github'),
  mode: like('FULL'),
  status: like('FAILED'),
  startedAt: regex(INSTANT, '2026-09-01T10:00:00Z'),
  durationMs: integer(90000),
  nodesUpserted: integer(3),
  edgesUpserted: integer(2),
  tombstones: integer(1),
  error: like('page 2 failed')
}

describe('sync run API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('lists runs a page at a time, filtered, with the totals', async () => {
    provider
      .given(FAILED_RUN_EXISTS)
      .uponReceiving('a request for the FAILED runs of github')
      .withRequest({
        method: 'GET',
        path: '/api/v1/sync-runs',
        query: { connector: 'github', status: 'FAILED', page: '0', size: '20' }
      })
      .willRespondWith({
        status: 200,
        body: {
          items: eachLike(summary),
          page: integer(0),
          size: integer(20),
          totalElements: integer(1),
          totalPages: integer(1)
        }
      })

    await provider.executeTest(async mockServer => {
      const page = await against(mockServer.url, () =>
        syncRunApi.list({ connector: 'github', status: 'FAILED', page: 0, size: 20 })
      )
      expect(page.items[0]).toMatchObject({ id: RUN_ID, status: 'FAILED' })
      expect(page.totalElements).toBe(1)
    })
  })

  it('reads one run in full, with its details and whole error', async () => {
    provider
      .given(FAILED_RUN_EXISTS)
      .uponReceiving('a request for one sync run')
      .withRequest({ method: 'GET', path: `/api/v1/sync-runs/${RUN_ID}` })
      .willRespondWith({
        status: 200,
        body: { ...summary, id: RUN_ID, details: like({}) }
      })

    await provider.executeTest(async mockServer => {
      const run = await against(mockServer.url, () => syncRunApi.get(RUN_ID))
      expect(run.id).toBe(RUN_ID)
      expect(run.details).toEqual({})
    })
  })

  it('says a run that was never recorded is not found', async () => {
    provider
      .given(EMPTY_GRAPH)
      .uponReceiving('a request for a sync run that does not exist')
      .withRequest({ method: 'GET', path: '/api/v1/sync-runs/no-such-run' })
      .willRespondWith({
        status: 404,
        body: { error: like('sync run not found'), id: 'no-such-run' }
      })

    await provider.executeTest(async mockServer => {
      await expect(against(mockServer.url, () => syncRunApi.get('no-such-run'))).rejects.toThrow()
    })
  })

  it('lists connectors with their freshness', async () => {
    provider
      .given(EMPTY_GRAPH)
      .uponReceiving('a request for the connectors')
      .withRequest({ method: 'GET', path: '/api/v1/connectors' })
      .willRespondWith({
        status: 200,
        body: eachLike({
          name: like('github'),
          sourceSystem: like('github'),
          enabled: boolean(false),
          health: { status: like('DOWN') },
          freshness: { thresholdSeconds: integer(7200), stale: boolean(false) }
        })
      })

    await provider.executeTest(async mockServer => {
      const connectors = await against(mockServer.url, () => connectorApi.list())
      expect(connectors[0].freshness).toMatchObject({ thresholdSeconds: 7200, stale: false })
    })
  })

  it('re-runs a connector, answering with the run to follow', async () => {
    provider
      .given(EMPTY_GRAPH)
      .uponReceiving('a request to sync github in full')
      .withRequest({
        method: 'POST',
        path: '/api/v1/connectors/github/sync',
        query: { mode: 'full' }
      })
      .willRespondWith({
        status: 202,
        headers: JSON_HEADERS,
        body: { syncRunId: like('0b8f2c1e') }
      })

    await provider.executeTest(async mockServer => {
      const accepted = await against(mockServer.url, () => connectorApi.sync('github', 'full'))
      expect(accepted.syncRunId).toBe('0b8f2c1e')
    })
  })
})
