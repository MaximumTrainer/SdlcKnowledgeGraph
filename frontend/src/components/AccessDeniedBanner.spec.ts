import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import axios from 'axios'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import AccessDeniedBanner from './AccessDeniedBanner.vue'
import { lastPolicyDenial, watchPolicyDenials } from '@/services/policyApi'

/**
 * The banner that says the authorisation policy refused something (#30): which rule and why. A
 * refusal for want of a scope is the page's to explain, so it raises no banner.
 */
const DENIAL = {
  error: 'policy denied',
  policy: 'roles',
  reason: 'the role viewer may not update Repository'
}

const mountBanner = async () => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<p>home</p>' } },
      { path: '/elsewhere', component: { template: '<p>elsewhere</p>' } }
    ]
  })
  router.push('/')
  await router.isReady()
  const wrapper = mount(AccessDeniedBanner, { global: { plugins: [router] } })
  return { wrapper, router }
}

const refusedWith = async (status: number, body: Record<string, unknown>) => {
  const client = axios.create({ baseURL: '/api/v1' })
  watchPolicyDenials(client)
  server.use(http.put('/api/v1/refused', () => HttpResponse.json(body, { status })))
  await client.put('/refused').catch(() => undefined)
  await flushPromises()
}

describe('AccessDeniedBanner', () => {
  afterEach(() => {
    lastPolicyDenial.value = null
  })

  it('shows nothing until the policy refuses something', async () => {
    const { wrapper } = await mountBanner()

    expect(wrapper.find('[data-test="access-denied"]').exists()).toBe(false)
  })

  it('names the rule that refused a request and why', async () => {
    const { wrapper } = await mountBanner()

    await refusedWith(403, DENIAL)

    expect(wrapper.find('[data-test="access-denied-policy"]').text()).toBe('roles')
    expect(wrapper.find('[data-test="access-denied-reason"]').text()).toBe(DENIAL.reason)
  })

  it('leaves a missing scope to the page that got it', async () => {
    const { wrapper } = await mountBanner()

    await refusedWith(403, { error: 'insufficient scope', required: ['graph:write'], held: [] })

    expect(wrapper.find('[data-test="access-denied"]').exists()).toBe(false)
  })

  it('goes away when dismissed, or on another page', async () => {
    const { wrapper, router } = await mountBanner()

    await refusedWith(403, DENIAL)
    await wrapper.find('[data-test="access-denied-dismiss"]').trigger('click')
    expect(wrapper.find('[data-test="access-denied"]').exists()).toBe(false)

    await refusedWith(403, DENIAL)
    await router.push('/elsewhere')
    await flushPromises()
    expect(wrapper.find('[data-test="access-denied"]').exists()).toBe(false)
  })
})
