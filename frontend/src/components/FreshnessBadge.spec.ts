import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FreshnessBadge from './FreshnessBadge.vue'

/**
 * Freshness decides whether an answer from the graph can be trusted (#29), so a connector past its
 * threshold is flagged in words, not only in colour, and one that never succeeded says "never".
 */
const freshness = (overrides = {}) => ({
  lastSuccessAt: '2026-09-29T09:00:00Z',
  ageSeconds: 600,
  thresholdSeconds: 7200,
  stale: false,
  ...overrides
})

describe('FreshnessBadge', () => {
  it('shows how long ago the connector last succeeded', () => {
    const wrapper = mount(FreshnessBadge, { props: { name: 'fake', freshness: freshness() } })

    expect(wrapper.text()).toContain('10 m ago')
    expect(wrapper.find('[data-test="stale-fake"]').exists()).toBe(false)
  })

  it('flags a connector past its threshold as stale, and says what the threshold is', () => {
    const wrapper = mount(FreshnessBadge, {
      props: { name: 'fake', freshness: freshness({ ageSeconds: 10_800, stale: true }) }
    })

    const badge = wrapper.find('[data-test="stale-fake"]')
    expect(badge.text()).toBe('stale')
    expect(wrapper.text()).toContain('3 h ago')
    expect(wrapper.attributes('title')).toContain('2 h')
  })

  it('says never for a connector that has not succeeded yet', () => {
    const wrapper = mount(FreshnessBadge, {
      props: { name: 'fake', freshness: freshness({ lastSuccessAt: null, ageSeconds: null }) }
    })

    expect(wrapper.text()).toContain('never')
  })
})
