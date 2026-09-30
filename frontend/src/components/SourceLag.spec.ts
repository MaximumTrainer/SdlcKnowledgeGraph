import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import SourceLag from './SourceLag.vue'

/**
 * How far behind each source is (#93, FR-6). Only a source past its window is worth a reader's
 * attention, so only those are shown, and in words: the warning must not depend on a colour.
 */
const lag = (overrides = {}) => ({
  source: 'aws',
  window: 'PT6H',
  windowSeconds: 21600,
  lastSuccessAt: '2026-09-29T06:00:00Z',
  lagSeconds: 108000,
  lagging: true,
  ...overrides
})

const answering = (sources: unknown[]) =>
  server.use(http.get('/api/v1/freshness', () => HttpResponse.json({ sources })))

const mounted = async () => {
  const wrapper = mount(SourceLag)
  await flushPromises()
  return wrapper
}

describe('SourceLag', () => {
  it('names a source behind its window, how far behind, and the window', async () => {
    answering([lag()])
    const wrapper = await mounted()

    const aws = wrapper.find('[data-test="source-lag-aws"]')
    expect(aws.text()).toContain('aws')
    expect(aws.text()).toMatch(/behind/i)
    expect(aws.text()).toContain('1 d 6 h')
    expect(aws.text()).toContain('6 h')
  })

  it('says a source that has never succeeded has never synced', async () => {
    answering([lag({ source: 'github', lastSuccessAt: null, lagSeconds: null })])
    const wrapper = await mounted()

    expect(wrapper.find('[data-test="source-lag-github"]').text()).toMatch(/never/i)
  })

  it('shows nothing for a source within its window', async () => {
    answering([lag({ lagging: false, lagSeconds: 600 })])
    const wrapper = await mounted()

    expect(wrapper.find('[data-test="source-lag-aws"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="source-lag"]').exists()).toBe(false)
  })

  it('shows nothing, rather than an error, when lag cannot be read', async () => {
    server.use(http.get('/api/v1/freshness', () => new HttpResponse(null, { status: 500 })))
    const wrapper = await mounted()

    expect(wrapper.find('[data-test="source-lag"]').exists()).toBe(false)
  })
})
