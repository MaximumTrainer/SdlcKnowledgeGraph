import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import LifecycleView from './LifecycleView.vue'
import { providing, READ_ONLY, sessionWith } from '@/test/authSession'

/**
 * The data lifecycle page (#33): which ontology version the graph is on and what is pending, whether
 * the archive is on, and each connector's rules for what it stops reporting. Applying migrations
 * changes every node they touch, so it is offered only to an admin.
 */
const ADMIN = ['graph:read', 'graph:write', 'graph:admin']

const status = (overrides: Record<string, unknown> = {}) => ({
  registryVersion: '1.4.0',
  migrations: {
    registryVersion: '1.4.0',
    dbVersion: '1.4.0',
    mode: 'auto',
    upToDate: true,
    pending: [],
    applied: []
  },
  versioning: { enabled: true, maxVersions: 50, excludedTypes: ['SyncRun'] },
  archive: {
    enabled: false,
    mode: 'dry-run',
    retention: 'P365D',
    schedule: '0 0 4 * * *',
    cutoff: '2025-09-30T12:00:00Z',
    eligible: { nodes: 3, edges: 1 }
  },
  connectors: [
    {
      name: 'github',
      sourceSystem: 'github',
      missingFromFullSync: 'tombstone',
      gracePeriod: 'P0D',
      requireSuccessfulRun: true,
      fullSyncIsComplete: true
    },
    {
      name: 'servicenow',
      sourceSystem: 'servicenow',
      missingFromFullSync: 'ignore',
      gracePeriod: 'P7D',
      requireSuccessfulRun: true,
      fullSyncIsComplete: false
    }
  ],
  ...overrides
})

const behind = status({
  migrations: {
    registryVersion: '1.4.0',
    dbVersion: '1.3.0',
    mode: 'manual',
    upToDate: false,
    pending: [
      { version: '1.4.0', name: 'rename_ci_legacy_name', checksum: 'abc', description: 'x' }
    ],
    applied: []
  }
})

const mountAs = async (scopes: string[] | null) => {
  const wrapper = mount(LifecycleView, {
    global: { provide: providing(scopes ? sessionWith(scopes) : null) }
  })
  await flushPromises()
  return wrapper
}

describe('LifecycleView', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/lifecycle', () => HttpResponse.json(status())))
  })

  it('says the graph is up to date with the ontology', async () => {
    const wrapper = await mountAs(READ_ONLY)

    expect(wrapper.find('h1').text()).toBe('Data lifecycle')
    expect(wrapper.find('[data-test="migrations-status"]').text()).toContain('up to date')
    expect(wrapper.find('[data-test="migrations-status"]').text()).toContain('1.4.0')
  })

  it('says the archive is off, and offers no way to run it', async () => {
    const wrapper = await mountAs(ADMIN)

    expect(wrapper.find('[data-test="archive-status"]').text()).toContain('off')
    expect(wrapper.find('[data-test="archive-run"]').exists()).toBe(false)
  })

  it("lists each connector's rules for what it stops reporting", async () => {
    const wrapper = await mountAs(READ_ONLY)

    const rows = wrapper.findAll('[data-test="connector-rules"] tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('github')
    expect(rows[0].text()).toContain('tombstone')
    expect(rows[1].text()).toContain('ignore')
    expect(rows[1].text()).toContain('7 days')
  })

  it('shows the pending migrations and lets an admin apply them', async () => {
    server.use(
      http.get('/api/v1/lifecycle', () => HttpResponse.json(behind)),
      http.post('/api/v1/lifecycle/migrations/apply', () => {
        server.use(http.get('/api/v1/lifecycle', () => HttpResponse.json(status())))
        return HttpResponse.json({ applied: [], dbVersion: '1.4.0' })
      })
    )
    const wrapper = await mountAs(ADMIN)

    expect(wrapper.find('[data-test="migrations-status"]').text()).toContain('1.3.0')
    expect(wrapper.text()).toContain('rename_ci_legacy_name')

    await wrapper.find('[data-test="migrations-apply"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="migrations-status"]').text()).toContain('up to date')
  })

  it('does not offer to apply migrations to someone who is not an admin', async () => {
    server.use(http.get('/api/v1/lifecycle', () => HttpResponse.json(behind)))

    const writer = await mountAs(['graph:read', 'graph:write'])
    const anonymous = await mountAs(null)

    expect(writer.find('[data-test="migrations-apply"]').exists()).toBe(false)
    expect(anonymous.find('[data-test="migrations-apply"]').exists()).toBe(false)
  })

  it('offers an admin a dry run when the archive is on', async () => {
    server.use(
      http.get('/api/v1/lifecycle', () =>
        HttpResponse.json(
          status({
            archive: { ...status().archive, enabled: true, mode: 'dry-run' }
          })
        )
      ),
      http.post('/api/v1/lifecycle/archive', ({ request }) => {
        expect(new URL(request.url).searchParams.get('dryRun')).toBe('true')
        return HttpResponse.json({
          dryRun: true,
          mode: 'dry-run',
          cutoff: '2025-09-30T12:00:00Z',
          wouldArchive: { nodes: 3, edges: 1 }
        })
      })
    )
    const wrapper = await mountAs(ADMIN)

    expect(wrapper.find('[data-test="archive-status"]').text()).toContain('dry-run')
    await wrapper.find('[data-test="archive-run"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="archive-result"]').text()).toContain('3 nodes')
  })
})
