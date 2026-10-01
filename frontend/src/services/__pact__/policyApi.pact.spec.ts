import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient } from '../api'
import { policyApi } from '../policyApi'

const { like, eachLike, boolean, regex } = MatchersV3

/**
 * The contract for the authorisation policy page (#30, #95): which policy is in force, and what it
 * decides for the signed-in user.
 *
 * The revision and when it was loaded are the provider's business, so they are matched by shape.
 * What the page relies on is the verdict, the rule that gave it and why, and the caller as the policy
 * saw them. The provider replays this as a user holding every graph scope and no roles claim, whom
 * the shipped policy allows everything and clears for everything.
 */
const DEFAULTS = 'the policy is at its defaults'
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

describe('policy API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('reads the policy in force: its revision, engine and fail mode', async () => {
    provider
      .given(DEFAULTS)
      .uponReceiving('a request for the policy in force')
      .withRequest({ method: 'GET', path: '/api/v1/policy' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          name: like('sdlc-authz'),
          revision: like('sdlc-authz-1.0.0'),
          loadedAt: regex(INSTANT, '2026-10-01T12:00:00Z'),
          engine: like('embedded-wasm'),
          status: like('UP'),
          failMode: 'closed'
        }
      })

    await provider.executeTest(async mockServer => {
      const status = await against(mockServer.url, () => policyApi.status())

      expect(status.failMode).toBe('closed')
      expect(status.revision).toBe('sdlc-authz-1.0.0')
    })
  })

  it('explains what the policy decides for the caller, by which rule and why', async () => {
    provider
      .given(DEFAULTS)
      .uponReceiving('a request to explain updating a repository')
      .withRequest({
        method: 'POST',
        path: '/api/v1/policy/explain',
        headers: JSON_HEADERS,
        body: { action: 'update', resource: { type: 'Repository' } }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          allow: boolean(true),
          policy: like('allow'),
          reason: like('the token holds graph:write'),
          required: eachLike('graph:write'),
          redact: like([]),
          clearance: like('restricted'),
          subject: {
            id: like('test-principal'),
            kind: like('user'),
            scopes: eachLike('graph:write'),
            roles: null,
            teams: like([])
          }
        }
      })

    await provider.executeTest(async mockServer => {
      const explanation = await against(mockServer.url, () =>
        policyApi.explain('update', { type: 'Repository' })
      )

      expect(explanation.allow).toBe(true)
      expect(explanation.subject.roles).toBeNull()
    })
  })
})
