import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import NodeHistory from './NodeHistory.vue'

/**
 * A node's history on its page (#33): the values it holds now, then each set it held before, newest
 * first, with when each held and, for a retirement, why it ended.
 */
const PAYMENTS = 'Repository:github.com/acme/payments'

describe('NodeHistory', () => {
  it('lists the current values first and then each earlier version', async () => {
    server.use(
      http.get('/api/v1/lifecycle/history', ({ request }) => {
        expect(new URL(request.url).searchParams.get('nodeId')).toBe(PAYMENTS)
        return HttpResponse.json({
          nodeId: PAYMENTS,
          current: {
            validFrom: '2026-01-01T00:00:00Z',
            validTo: null,
            propsFrom: '2026-03-01T00:00:00Z',
            retired: false,
            retiredReason: null,
            resurrectedAt: null,
            props: { description: 'v3' }
          },
          versions: [
            {
              validFrom: '2026-02-01T00:00:00Z',
              validTo: '2026-03-01T00:00:00Z',
              retired: true,
              retiredReason: 'missing-from-sync',
              props: { description: 'v2' },
              provenance: { sourceSystem: 'github' }
            },
            {
              validFrom: '2026-01-01T00:00:00Z',
              validTo: '2026-02-01T00:00:00Z',
              retired: false,
              retiredReason: null,
              props: { description: 'v1' },
              provenance: { sourceSystem: 'manual' }
            }
          ]
        })
      })
    )
    const wrapper = mount(NodeHistory, { props: { nodeId: PAYMENTS } })
    await flushPromises()

    const entries = wrapper.findAll('[data-test="history-entry"]')
    expect(entries).toHaveLength(3)
    expect(entries[0].text()).toContain('v3')
    expect(entries[0].text()).toContain('current')
    expect(entries[1].text()).toContain('v2')
    expect(entries[1].text()).toContain('missing-from-sync')
    expect(entries[2].text()).toContain('v1')
    expect(entries[2].text()).toContain('manual')
  })

  it('says so when a node has never changed', async () => {
    server.use(
      http.get('/api/v1/lifecycle/history', () =>
        HttpResponse.json({
          nodeId: PAYMENTS,
          current: {
            validFrom: '2026-01-01T00:00:00Z',
            validTo: null,
            propsFrom: '2026-01-01T00:00:00Z',
            retired: false,
            retiredReason: null,
            resurrectedAt: null,
            props: { description: 'v1' }
          },
          versions: []
        })
      )
    )
    const wrapper = mount(NodeHistory, { props: { nodeId: PAYMENTS } })
    await flushPromises()

    expect(wrapper.findAll('[data-test="history-entry"]')).toHaveLength(1)
    expect(wrapper.find('[data-test="history-empty"]').text()).toContain('no earlier versions')
  })
})
