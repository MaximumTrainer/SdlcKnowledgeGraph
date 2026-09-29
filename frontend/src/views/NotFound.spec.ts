import { describe, expect, it } from 'vitest'
import { mount, RouterLinkStub } from '@vue/test-utils'
import NotFound from './NotFound.vue'

describe('NotFound', () => {
  it('says the page does not exist and leads back to the graph', () => {
    const wrapper = mount(NotFound, { global: { stubs: { RouterLink: RouterLinkStub } } })

    expect(wrapper.find('h1').text()).toBe('Page not found')
    const home = wrapper.findComponent(RouterLinkStub)
    expect(home.props('to')).toBe('/')
    expect(home.text()).toBe('Back to the graph')
  })
})
