import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient } from '../api'
import { linkApi } from '../linkApi'

const { like, eachLike, integer, decimal, boolean, regex } = MatchersV3

/**
 * The contract for the review of the link engine's work (#28): the candidates it proposed, a
 * decision on one, and a resolution started.
 *
 * What the review page relies on is the shape: each candidate's resource and repository, its rule,
 * confidence, evidence and status, the page's totals, the owner an acceptance states, and the sync
 * run a resolution answers with. Which candidates exist is the provider's business, so the list is
 * matched by shape; the one decided on is seeded under a known id.
 */
const PENDING_CANDIDATE =
  'a pending naming candidate links the billing queue to github.com/acme/billing'
const NOTHING_TO_RESOLVE = 'no repositories exist'
const CANDIDATE_ID = 'pact-candidate-1'
const QUEUE = 'aws:arn:aws:sqs:eu-west-1:111111111111:billing-prod'

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

const resource = {
  key: like(QUEUE),
  name: like('billing-prod'),
  provider: like('aws'),
  accountId: like('111111111111')
}
const repository = { key: like('github.com/acme/billing'), name: like('billing') }

describe('link API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('lists the pending candidates a page at a time, strongest first', async () => {
    provider
      .given(PENDING_CANDIDATE)
      .uponReceiving('a request for the pending link candidates')
      .withRequest({
        method: 'GET',
        path: '/api/v1/links/candidates',
        query: { status: 'pending', page: '0', size: '50' }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          items: eachLike({
            id: like(CANDIDATE_ID),
            resource,
            repository,
            confidence: decimal(0.4),
            rule: like('naming'),
            evidence: like({ name: 'billing-prod' }),
            status: like('pending'),
            createdAt: regex(INSTANT, '2026-09-30T12:00:00Z')
          }),
          page: integer(0),
          size: integer(50),
          totalElements: integer(1),
          totalPages: integer(1)
        }
      })

    await provider.executeTest(async mockServer => {
      const page = await against(mockServer.url, () =>
        linkApi.candidates({ status: 'pending', page: 0, size: 50 })
      )

      expect(page.items[0].rule).toBe('naming')
      expect(page.items[0].resource.name).toBe('billing-prod')
      expect(page.totalElements).toBe(1)
    })
  })

  it('accepts a candidate, answering with the owner it states', async () => {
    provider
      .given(PENDING_CANDIDATE)
      .uponReceiving('a request to accept a pending link candidate')
      .withRequest({ method: 'POST', path: `/api/v1/links/candidates/${CANDIDATE_ID}/accept` })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          resource,
          repository,
          rule: 'manual',
          confidence: decimal(1.0),
          inferred: boolean(false),
          evidence: like({ name: 'billing-prod' }),
          acceptedBy: like('test-principal'),
          sourceSystem: 'manual'
        }
      })

    await provider.executeTest(async mockServer => {
      const owner = await against(mockServer.url, () => linkApi.accept(CANDIDATE_ID))

      expect(owner.rule).toBe('manual')
      expect(owner.inferred).toBe(false)
    })
  })

  it('rejects a candidate, answering with it rejected', async () => {
    provider
      .given(PENDING_CANDIDATE)
      .uponReceiving('a request to reject a pending link candidate')
      .withRequest({ method: 'POST', path: `/api/v1/links/candidates/${CANDIDATE_ID}/reject` })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: CANDIDATE_ID,
          resource,
          repository,
          rule: like('naming'),
          status: 'rejected',
          rejectedBy: like('test-principal'),
          rejectedAt: regex(INSTANT, '2026-10-01T09:00:00Z')
        }
      })

    await provider.executeTest(async mockServer => {
      const rejected = await against(mockServer.url, () => linkApi.reject(CANDIDATE_ID))

      expect(rejected.status).toBe('rejected')
    })
  })

  it('starts a resolution, answering 202 with its sync run', async () => {
    provider
      .given(NOTHING_TO_RESOLVE)
      .uponReceiving('a request to resolve every link')
      .withRequest({
        method: 'POST',
        path: '/api/v1/links/resolve',
        headers: { 'Content-Type': 'application/json' },
        body: {}
      })
      .willRespondWith({
        status: 202,
        headers: JSON_HEADERS,
        body: { syncRunId: like('0b9f4d1e-6c1a-4b8e-9f2a-3c5d7e9f1a2b'), mode: 'FULL' }
      })

    await provider.executeTest(async mockServer => {
      const started = await against(mockServer.url, () => linkApi.resolve())

      expect(started.mode).toBe('FULL')
      expect(started.syncRunId).toBeTruthy()
    })
  })
})
