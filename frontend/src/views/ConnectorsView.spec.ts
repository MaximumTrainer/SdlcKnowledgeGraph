import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import ConnectorsView from './ConnectorsView.vue'

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
    lastRun: { id: 'run-1', status: 'SUCCESS', finishedAt: '2026-09-01T10:00:00Z', watermark: null }
  },
  {
    name: 'sleeping',
    sourceSystem: 'sleeping',
    enabled: false,
    capabilities: ['FULL'],
    health: { status: 'DOWN', detail: 'no credentials' },
    lastRun: null
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
