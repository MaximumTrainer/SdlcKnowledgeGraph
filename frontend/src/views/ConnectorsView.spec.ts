import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import ConnectorsView from './ConnectorsView.vue'
import {
  insufficientScope,
  providing,
  READ_ONLY,
  READ_WRITE,
  sessionWith
} from '@/test/authSession'

/**
 * The connectors screen answers one question before any other: is anything actually ingesting?
 *
 * So enabled state and health are the things asserted here. A connector that is off, or whose source
 * is unreachable, has to say so on the screen - otherwise "the graph looks empty" has no visible
 * cause and someone goes looking in the logs.
 */
const connectors = [
  {
    name: 'fake',
    sourceSystem: 'fake',
    enabled: true,
    capabilities: ['FULL', 'INCREMENTAL', 'WEBHOOK'],
    health: { status: 'UP', detail: 'scripted' },
    lastRun: {
      id: 'run-1',
      status: 'SUCCESS',
      finishedAt: '2026-09-01T10:00:00Z',
      watermark: null
    },
    freshness: {
      lastSuccessAt: '2026-09-01T10:00:00Z',
      ageSeconds: 10_800,
      thresholdSeconds: 3600,
      stale: true
    }
  },
  {
    name: 'sleeping',
    sourceSystem: 'sleeping',
    enabled: false,
    capabilities: ['FULL'],
    health: { status: 'DOWN', detail: 'no credentials' },
    lastRun: null,
    freshness: { lastSuccessAt: null, ageSeconds: null, thresholdSeconds: 7200, stale: false }
  }
]

describe('ConnectorsView', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/connectors', () => HttpResponse.json(connectors)))
  })

  const mountView = async () => {
    const wrapper = mount(ConnectorsView)
    await flushPromises()
    return wrapper
  }

  it('lists every connector with its source system', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('fake')
    expect(wrapper.text()).toContain('sleeping')
  })

  it('says which connectors are actually running and which are not', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="enabled-fake"]').text()).toBe('enabled')
    expect(wrapper.find('[data-test="enabled-sleeping"]').text()).toBe('disabled')
  })

  it('shows health, so an unreachable source has a visible cause', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="health-sleeping"]').text()).toContain('DOWN')
  })

  it('shows the last run status', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="last-run-fake"]').text()).toContain('SUCCESS')
    expect(wrapper.find('[data-test="last-run-sleeping"]').text()).toContain('never')
  })

  it('shows the last run status as a chip', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="last-run-fake"] [data-test="status-chip"]').text()).toBe(
      'SUCCESS'
    )
  })

  /** An answer built on a stale connector is worse than none, so staleness is flagged in words. */
  it('shows how old each connector’s last success is, and flags a stale one', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="freshness-fake"]').text()).toContain('3 h ago')
    expect(wrapper.find('[data-test="stale-fake"]').text()).toBe('stale')
    expect(wrapper.find('[data-test="freshness-sleeping"]').text()).toContain('never')
    expect(wrapper.find('[data-test="stale-sleeping"]').exists()).toBe(false)
  })

  it('asks for a sync and reports the run it started', async () => {
    server.use(
      http.post('/api/v1/connectors/fake/sync', () =>
        HttpResponse.json({ syncRunId: 'run-2' }, { status: 202 })
      )
    )
    const wrapper = await mountView()

    await wrapper.find('[data-test="sync-fake"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="sync-result"]').text()).toContain('run-2')
  })

  /** A disabled connector is not scheduled, so offering to sync it would promise something untrue. */
  it('does not offer to sync a connector that is disabled', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="sync-sleeping"]').exists()).toBe(false)
  })
})

/** Starting a sync writes to the graph, so it needs graph:write (#116). */
describe('ConnectorsView, for a user who may only read', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/connectors', () => HttpResponse.json(connectors)))
  })

  const viewAs = async (scopes: string[]) => {
    const wrapper = mount(ConnectorsView, {
      global: { provide: providing(sessionWith(scopes)) }
    })
    await flushPromises()
    return wrapper
  }

  it('shows every connector but offers no sync', async () => {
    const wrapper = await viewAs(READ_ONLY)

    expect(wrapper.find('[data-test="enabled-fake"]').text()).toBe('enabled')
    expect(wrapper.find('[data-test="sync-fake"]').exists()).toBe(false)
  })

  it('says which scope was missing when a sync is refused for it', async () => {
    server.use(
      http.post('/api/v1/connectors/fake/sync', () =>
        HttpResponse.json(insufficientScope, { status: 403 })
      )
    )
    const wrapper = await viewAs(READ_WRITE)

    await wrapper.find('[data-test="sync-fake"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="sync-result"]').text()).toBe(
      'You do not have permission to do that: it needs graph:write, and you hold graph:read.'
    )
  })
})
