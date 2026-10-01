import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfidenceBar from './ConfidenceBar.vue'

/**
 * How sure the link engine is, read at a glance (#28, FR12): a bar as long as the confidence, the
 * percentage beside it, and the value exposed as a meter so a screen reader says it too. A link
 * below the threshold is drawn differently from one that would own the resource.
 */
describe('ConfidenceBar', () => {
  it('shows a confidence as a whole percentage, a bar that long, and a meter', () => {
    const wrapper = mount(ConfidenceBar, { props: { value: 0.4 } })

    const meter = wrapper.find('[data-test="confidence-bar"]')
    expect(meter.attributes('role')).toBe('meter')
    expect(meter.attributes('aria-valuenow')).toBe('40')
    expect(meter.attributes('aria-valuemin')).toBe('0')
    expect(meter.attributes('aria-valuemax')).toBe('100')
    expect(meter.text()).toContain('40%')
    expect(wrapper.find('[data-test="confidence-fill"]').attributes('style')).toContain(
      'width: 40%'
    )
  })

  it('rounds to the nearest percent', () => {
    const wrapper = mount(ConfidenceBar, { props: { value: 0.955 } })

    expect(wrapper.find('[data-test="confidence-bar"]').attributes('aria-valuenow')).toBe('96')
  })

  it('keeps a value outside 0 to 1 inside the bar', () => {
    expect(
      mount(ConfidenceBar, { props: { value: 1.7 } })
        .find('[data-test="confidence-bar"]')
        .attributes('aria-valuenow')
    ).toBe('100')
    expect(
      mount(ConfidenceBar, { props: { value: -0.2 } })
        .find('[data-test="confidence-bar"]')
        .attributes('aria-valuenow')
    ).toBe('0')
  })

  it('draws a confidence below the threshold as weak and one at or above it as strong', () => {
    const weak = mount(ConfidenceBar, { props: { value: 0.4, threshold: 0.5 } })
    const strong = mount(ConfidenceBar, { props: { value: 0.5, threshold: 0.5 } })

    expect(weak.find('[data-test="confidence-bar"]').classes()).toContain('weak')
    expect(strong.find('[data-test="confidence-bar"]').classes()).toContain('strong')
  })
})
