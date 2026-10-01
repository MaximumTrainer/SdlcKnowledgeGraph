import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import EvidenceList from './EvidenceList.vue'

/** What a link rests on (#28, FR12), each piece named, so a reviewer can check it before deciding. */
describe('EvidenceList', () => {
  it('lists each piece of evidence by name, in name order', () => {
    const wrapper = mount(EvidenceList, {
      props: { evidence: { path: 'infra/main.tf', matched: 'name', reference: 'acme-logs' } }
    })

    const list = wrapper.find('[data-test="evidence-list"]')
    expect(list.findAll('dt').map(term => term.text())).toEqual(['matched', 'path', 'reference'])
    expect(list.findAll('dd').map(value => value.text())).toEqual([
      'name',
      'infra/main.tf',
      'acme-logs'
    ])
  })

  it('says when nothing was recorded', () => {
    const wrapper = mount(EvidenceList, { props: { evidence: {} } })

    expect(wrapper.find('[data-test="evidence-list"]').text()).toContain('No evidence recorded')
  })
})
