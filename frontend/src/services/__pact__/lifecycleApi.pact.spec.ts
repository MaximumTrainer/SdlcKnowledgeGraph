import { describe, it, expect, afterAll } from 'vitest'
import { PactV3, MatchersV3 } from '@pact-foundation/pact'
import { apiClient } from '../api'
import { lifecycleApi } from '../lifecycleApi'

const { like, eachLike, integer, boolean, regex } = MatchersV3

/**
 * The contract for the data lifecycle (#33): the administration page's status, applying the
 * ontology migrations, rehearsing the archive, and a node's history on its page.
 *
 * Which migrations a build ships, which connectors exist and how many facts the archive would take
 * are the provider's business, so they are matched by shape. What the screens rely on is the shape:
 * the versions and whether the graph is up to date, the archive's settings and counts, each
 * connector's rules, and a history's current validity beside its earlier versions.
 */
const DEFAULTS = 'the lifecycle is at its defaults'
const BEHIND = 'the graph is on the previous ontology version'
const RETIRED_LONG_AGO = 'a fact retired long ago'
const ONE_EARLIER_VERSION = 'the payments repository has one earlier version'

const PAYMENTS = 'Repository:github.com/acme/payments'
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/
const SEMVER = /^\d+\.\d+\.\d+$/
const DURATION = /^P(\d+D|T.+)$/
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

const counts = { nodes: integer(0), edges: integer(0) }

describe('lifecycle API contract', () => {
  afterAll(() => {
    apiClient.defaults.baseURL = '/api/v1'
  })

  it('reads the lifecycle status: migrations, versioning, the archive and each connector', async () => {
    provider
      .given(DEFAULTS)
      .uponReceiving('a request for the lifecycle status')
      .withRequest({ method: 'GET', path: '/api/v1/lifecycle' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          registryVersion: regex(SEMVER, '1.4.0'),
          migrations: {
            registryVersion: regex(SEMVER, '1.4.0'),
            dbVersion: regex(SEMVER, '1.4.0'),
            mode: like('auto'),
            upToDate: boolean(true),
            pending: like([]),
            applied: like([])
          },
          versioning: {
            enabled: boolean(true),
            maxVersions: integer(50),
            excludedTypes: eachLike('SyncRun')
          },
          archive: {
            enabled: boolean(false),
            mode: like('dry-run'),
            retention: regex(DURATION, 'P365D'),
            schedule: like('0 0 4 * * *'),
            cutoff: regex(INSTANT, '2025-09-30T12:00:00Z'),
            eligible: counts
          },
          connectors: eachLike({
            name: like('github'),
            sourceSystem: like('github'),
            missingFromFullSync: like('tombstone'),
            gracePeriod: regex(DURATION, 'P0D'),
            requireSuccessfulRun: boolean(true),
            fullSyncIsComplete: boolean(true)
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const status = await against(mockServer.url, () => lifecycleApi.status())

      expect(status.migrations.upToDate).toBe(true)
      expect(status.archive.enabled).toBe(false)
      expect(status.connectors[0].missingFromFullSync).toBe('tombstone')
    })
  })

  it('applies the pending migrations and answers with the version the graph is now on', async () => {
    provider
      .given(BEHIND)
      .uponReceiving('a request to apply the pending ontology migrations')
      .withRequest({ method: 'POST', path: '/api/v1/lifecycle/migrations/apply' })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: { applied: like([]), dbVersion: regex(SEMVER, '1.4.0') }
      })

    await provider.executeTest(async mockServer => {
      const result = await against(mockServer.url, () => lifecycleApi.applyMigrations())

      expect(result.dbVersion).toBe('1.4.0')
    })
  })

  it('rehearses the archive, answering with what it would take', async () => {
    provider
      .given(RETIRED_LONG_AGO)
      .uponReceiving('a request for an archive dry run')
      .withRequest({ method: 'POST', path: '/api/v1/lifecycle/archive', query: { dryRun: 'true' } })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          dryRun: boolean(true),
          mode: like('dry-run'),
          cutoff: regex(INSTANT, '2025-09-30T12:00:00Z'),
          wouldArchive: { nodes: integer(1), edges: integer(0) }
        }
      })

    await provider.executeTest(async mockServer => {
      const result = await against(mockServer.url, () => lifecycleApi.archive({ dryRun: true }))

      expect(result.dryRun).toBe(true)
      expect(result.wouldArchive?.nodes).toBe(1)
    })
  })

  it("reads a node's history: its current validity and its earlier versions", async () => {
    provider
      .given(ONE_EARLIER_VERSION)
      .uponReceiving('a request for the history of the payments repository')
      .withRequest({
        method: 'GET',
        path: '/api/v1/lifecycle/history',
        query: { nodeId: PAYMENTS }
      })
      .willRespondWith({
        status: 200,
        headers: JSON_HEADERS,
        body: {
          nodeId: PAYMENTS,
          current: {
            validFrom: regex(INSTANT, '2026-01-01T00:00:00Z'),
            propsFrom: regex(INSTANT, '2026-02-01T00:00:00Z'),
            retired: boolean(false),
            props: { description: like('v2') }
          },
          versions: eachLike({
            validFrom: regex(INSTANT, '2026-01-01T00:00:00Z'),
            validTo: regex(INSTANT, '2026-02-01T00:00:00Z'),
            retired: boolean(false),
            props: { description: like('v1') },
            provenance: { sourceSystem: like('manual') }
          })
        }
      })

    await provider.executeTest(async mockServer => {
      const history = await against(mockServer.url, () => lifecycleApi.history(PAYMENTS))

      expect(history.current.props.description).toBe('v2')
      expect(history.versions[0].props.description).toBe('v1')
    })
  })
})
