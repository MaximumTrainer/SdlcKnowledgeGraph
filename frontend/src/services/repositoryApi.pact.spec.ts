import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, repositoryApi, graphApi } from './api'

const { like, eachLike } = MatchersV3

/**
 * The consumer half of the contract. These are not tests of the backend: they record what this
 * frontend actually sends and what it needs back, by driving the real `repositoryApi` / `graphApi`
 * functions against a Pact mock server. The resulting pact in `contracts/pacts/` is replayed
 * against the running backend by `backend/src/contractTest` (see docs/adr/0004-pact-folder-no-broker.md).
 *
 * Provider states name the graph the backend must be in for the interaction to make sense; the
 * matching `@State` handlers live in `backend/src/contractTest/.../ProviderStates.kt`.
 */
const REPOSITORY_R1_EXISTS = 'a repository with id R1 exists'
const NO_REPOSITORIES = 'no repositories exist'

const provider = new PactV3({
  consumer: 'sdlc-graph-frontend',
  provider: 'sdlc-graph-backend',
  dir: '../contracts/pacts'
})

/** Points the shared axios instance at the mock server for one interaction. */
const against = async <T>(mockServerUrl: string, call: () => Promise<T>): Promise<T> => {
  apiClient.defaults.baseURL = `${mockServerUrl}/api/v1`
  return call()
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

describe('repository API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('reads one repository by id', async () => {
    provider
      .given(REPOSITORY_R1_EXISTS)
      .uponReceiving('a request for repository R1')
      .withRequest({ method: 'GET', path: '/api/v1/repositories/R1' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: like('R1'),
          orgRepo: like('acme/payments'),
          defaultBranch: like('main'),
          topics: eachLike('payments'),
          codeowners: eachLike('@acme/platform')
        }
      })

    await provider.executeTest(async mockServer => {
      const repository = await against(mockServer.url, () => repositoryApi.get('R1'))

      expect(repository.orgRepo).toBe('acme/payments')
      expect(repository.defaultBranch).toBe('main')
      expect(repository.topics).toEqual(['payments'])
      expect(repository.codeowners).toEqual(['@acme/platform'])
    })
  })

  it('lists repositories when the graph is empty', async () => {
    provider
      .given(NO_REPOSITORIES)
      .uponReceiving('a request for all repositories')
      .withRequest({ method: 'GET', path: '/api/v1/repositories' })
      .willRespondWith({ status: 200, headers: JSON_HEADERS, body: [] })

    await provider.executeTest(async mockServer => {
      const repositories = await against(mockServer.url, () => repositoryApi.list())

      expect(repositories).toEqual([])
    })
  })

  it('registers a repository', async () => {
    provider
      .given(NO_REPOSITORIES)
      .uponReceiving('a request to register acme/payments')
      .withRequest({
        method: 'POST',
        path: '/api/v1/repositories',
        headers: JSON_HEADERS,
        body: { orgRepo: 'acme/payments', defaultBranch: 'main', topics: [], codeowners: [] }
      })
      .willRespondWith({
        status: 201,
        headers: JSON_HEADERS,
        body: { id: like('Repository:github.com/acme/payments'), orgRepo: like('acme/payments') }
      })

    await provider.executeTest(async mockServer => {
      const created = await against(mockServer.url, () =>
        repositoryApi.create({
          orgRepo: 'acme/payments',
          defaultBranch: 'main',
          topics: [],
          codeowners: []
        })
      )

      expect(created.id).toBeTruthy()
    })
  })

  it('reads the impact analysis for a repository', async () => {
    provider
      .given(REPOSITORY_R1_EXISTS)
      .uponReceiving('a request for the impact of repository R1')
      .withRequest({ method: 'GET', path: '/api/v1/graph/repositories/R1/impact' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: { repoId: like('R1'), dependents: [], cloudResources: [], deployments: [] }
      })

    await provider.executeTest(async mockServer => {
      const impact = await against(mockServer.url, () => graphApi.getImpact('R1'))

      expect(impact.repoId).toBe('R1')
      expect(impact.dependents).toEqual([])
      expect(impact.cloudResources).toEqual([])
      expect(impact.deployments).toEqual([])
    })
  })
})
