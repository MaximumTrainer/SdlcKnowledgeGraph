import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusChip from './StatusChip.vue'

/** A status is read at a glance down a column, so each one has its own colour and its own word. */
describe('StatusChip', () => {
  it.each([
    ['SUCCESS', 'success'],
    ['PARTIAL', 'partial'],
    ['FAILED', 'failed'],
    ['RUNNING', 'running']
  ])('shows %s in its own style', (status, style) => {
    const chip = mount(StatusChip, { props: { status } }).find('[data-test="status-chip"]')

    expect(chip.text()).toBe(status)
    expect(chip.classes()).toContain(style)
  })

  it('says unknown for a run with no recorded status, rather than showing nothing', () => {
    const chip = mount(StatusChip, { props: { status: null } }).find('[data-test="status-chip"]')

    expect(chip.text()).toBe('UNKNOWN')
  })
})
