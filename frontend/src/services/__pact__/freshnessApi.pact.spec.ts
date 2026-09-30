import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient } from '../api'
import { freshnessApi } from '../freshnessApi'

const { like, eachLike, integer, boolean, regex } = MatchersV3

/**
 * The contract for how far behind each source is (#93, FR-3 and FR-6), which the home page shows.
 *
 * The health endpoint says the same, but the web interface proxies no health details, so the page
 * reads this instead. Which sources are listed is the provider's business; the shape of one entry,
 * and that a source past its window says so, is the contract.
 */
const GITHUB_BEHIND = 'the github source last synced thirty hours ago'

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

describe('source freshness API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('lists each source with its lag against its window', async () => {
    provider
      .given(GITHUB_BEHIND)
      .uponReceiving('a request for how far behind each source is')
      .withRequest({ method: 'GET', path: '/api/v1/freshness' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          sources: eachLike({
            source: like('github'),
            window: like('PT24H'),
            windowSeconds: integer(86400),
            lastSuccessAt: regex(INSTANT, '2026-09-29T06:00:00Z'),
            lagSeconds: integer(108000),
            lagging: boolean(true)
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const sources = await against(mockServer.url, () => freshnessApi.sources())

      expect(sources[0].source).toBe('github')
      expect(sources[0].lagging).toBe(true)
    })
  })
})
