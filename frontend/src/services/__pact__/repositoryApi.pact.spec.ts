import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient, repositoryApi, graphApi, nodeApi } from '../api'

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
const R1_RELATES_TO_A_CI = 'repository R1 is linked to a configuration item'
const GITHUB_123456 = 'repository github.com/acme/payments has GitHub id 123456'
const RENAMED_123456 =
  'repository with GitHub id 123456 was renamed from acme/payments to acme-platform/payments-service'

const PAYMENTS_ID = 'Repository:github.com/acme/payments'
const RENAMED_KEY = 'github.com/acme-platform/payments-service'

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
          url: like('https://github.com/acme/payments'),
          key: like('github.com/acme/payments'),
          defaultBranch: like('main'),
          topics: eachLike('payments'),
          codeowners: eachLike('@acme/platform')
        }
      })

    await provider.executeTest(async mockServer => {
      const repository = await against(mockServer.url, () => repositoryApi.get('R1'))

      expect(repository.url).toBe('https://github.com/acme/payments')
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
      .uponReceiving('a request to register the acme/payments remote')
      .withRequest({
        method: 'POST',
        path: '/api/v1/repositories',
        headers: JSON_HEADERS,
        body: { url: 'acme/payments', defaultBranch: 'main', topics: [], codeowners: [] }
      })
      .willRespondWith({
        status: 201,
        headers: JSON_HEADERS,
        body: {
          id: like('Repository:github.com/acme/payments'),
          url: like('https://github.com/acme/payments'),
          key: like('github.com/acme/payments')
        }
      })

    await provider.executeTest(async mockServer => {
      const created = await against(mockServer.url, () =>
        repositoryApi.create({
          // The shorthand, deliberately: the contract is that the API canonicalises it (#8).
          url: 'acme/payments',
          defaultBranch: 'main',
          topics: [],
          codeowners: []
        })
      )

      expect(created.id).toBeTruthy()
    })
  })

  /**
   * The narrow, older shape of a configuration item.
   *
   * Worth a contract of its own because it is the one endpoint that reports a registry-declared type
   * through a hand-written projection: `ConfigurationItem` has eleven properties and this sends four.
   * Anything that widened it - a connector adding a field, somebody returning the node itself - would
   * pass every backend test and still change what a caller receives. This is what notices.
   */
  it('reads the configuration item linked to a repository', async () => {
    provider
      .given(R1_RELATES_TO_A_CI)
      .uponReceiving('a request for the configuration item of repository R1')
      .withRequest({ method: 'GET', path: '/api/v1/graph/repositories/R1/servicenow' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: like('ConfigurationItem:servicenow:sn.example.test:a1'),
          ciName: like('payments-api'),
          serviceId: like('SVC-1'),
          repoId: like('R1')
        }
      })

    await provider.executeTest(async mockServer => {
      const ci = await against(mockServer.url, () => graphApi.getServiceNowCI('R1'))

      expect(ci?.ciName).toBe('payments-api')
      expect(ci?.serviceId).toBe('SVC-1')
      expect(ci?.repoId).toBe('R1')
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
  /**
   * A repository addressed by the id its provider gives it (#88), which survives a rename or a
   * transfer where the remote does not. What the consumer relies on: the node it finds, its provider
   * id, and the legacy `orgRepo`, still emitted though no longer accepted.
   */
  it('finds a repository by its provider id', async () => {
    provider
      .given(GITHUB_123456)
      .uponReceiving('a request for the repository with GitHub id 123456')
      .withRequest({ method: 'GET', path: '/api/v1/repositories/by-provider/github/123456' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: PAYMENTS_ID,
          key: 'github.com/acme/payments',
          url: like('https://github.com/acme/payments'),
          provider: 'github',
          providerId: '123456',
          orgRepo: 'acme/payments'
        }
      })

    await provider.executeTest(async mockServer => {
      const found = await against(mockServer.url, () =>
        repositoryApi.byProvider('github', '123456')
      )

      expect(found.id).toBe(PAYMENTS_ID)
      expect(found.providerId).toBe('123456')
      expect(found.orgRepo).toBe('acme/payments')
    })
  })

  it('answers 404 for a provider id nothing holds', async () => {
    provider
      .given(NO_REPOSITORIES)
      .uponReceiving('a request for a GitHub id no repository holds')
      .withRequest({ method: 'GET', path: '/api/v1/repositories/by-provider/github/999' })
      .willRespondWith({ status: 404 })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        repositoryApi.byProvider('github', '999').catch(error => error.response)
      )

      expect(failure.status).toBe(404)
    })
  })

  it('finds a renamed repository by the url it had before', async () => {
    provider
      .given(RENAMED_123456)
      .uponReceiving('a request for the repository at its old url')
      .withRequest({
        method: 'GET',
        path: '/api/v1/repositories',
        query: { url: 'https://github.com/acme/payments' }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: [
          {
            id: `Repository:${RENAMED_KEY}`,
            key: RENAMED_KEY,
            providerId: '123456',
            previousKeys: ['github.com/acme/payments']
          }
        ]
      })

    await provider.executeTest(async mockServer => {
      const found = await against(mockServer.url, () =>
        repositoryApi.byUrl('https://github.com/acme/payments')
      )

      expect(found.map(repository => repository.key)).toEqual([RENAMED_KEY])
      expect(found[0].previousKeys).toEqual(['github.com/acme/payments'])
    })
  })

  /**
   * The editing screen saves through the node API and then opens the key it is given back, so a
   * rename through the provider id has to answer with the node under its new key.
   */
  it('renames a repository in place when the provider id matches', async () => {
    provider
      .given(GITHUB_123456)
      .uponReceiving('a request to move the repository with GitHub id 123456 to a new url')
      .withRequest({
        method: 'PUT',
        path: '/api/v1/nodes/Repository/github.com/acme/payments',
        headers: JSON_HEADERS,
        body: {
          props: {
            url: 'https://github.com/acme-platform/payments-service',
            defaultBranch: 'main',
            topics: [],
            codeowners: [],
            provider: 'github',
            providerId: '123456'
          }
        }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          id: `Repository:${RENAMED_KEY}`,
          type: 'Repository',
          key: RENAMED_KEY,
          props: like({ providerId: '123456' }),
          provenance: like({ previousKeys: ['github.com/acme/payments'] })
        }
      })

    await provider.executeTest(async mockServer => {
      const renamed = await against(mockServer.url, () =>
        nodeApi.update('Repository', 'github.com/acme/payments', {
          url: 'https://github.com/acme-platform/payments-service',
          defaultBranch: 'main',
          topics: [],
          codeowners: [],
          provider: 'github',
          providerId: '123456'
        })
      )

      expect(renamed.key).toBe(RENAMED_KEY)
      expect(renamed.provenance.previousKeys).toEqual(['github.com/acme/payments'])
    })
  })

  it('refuses a second repository with a provider id another already holds', async () => {
    provider
      .given(GITHUB_123456)
      .uponReceiving('a request to create another repository with GitHub id 123456')
      .withRequest({
        method: 'POST',
        path: '/api/v1/nodes/Repository',
        headers: JSON_HEADERS,
        body: {
          props: {
            url: 'https://github.com/other/thing',
            defaultBranch: 'main',
            topics: [],
            codeowners: [],
            providerId: '123456'
          }
        }
      })
      .willRespondWith({
        status: 409,
        headers: JSON_HEADERS,
        body: {
          error: 'node exists',
          existingId: PAYMENTS_ID,
          alias: { provider: 'github', providerId: '123456' }
        }
      })

    await provider.executeTest(async mockServer => {
      const failure = await against(mockServer.url, () =>
        nodeApi
          .create('Repository', {
            url: 'https://github.com/other/thing',
            defaultBranch: 'main',
            topics: [],
            codeowners: [],
            providerId: '123456'
          })
          .catch(error => error.response)
      )

      expect(failure.status).toBe(409)
      expect(failure.data.existingId).toBe(PAYMENTS_ID)
    })
  })
})
