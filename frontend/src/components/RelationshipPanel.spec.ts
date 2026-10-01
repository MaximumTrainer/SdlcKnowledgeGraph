import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { delay, http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import RelationshipPanel from './RelationshipPanel.vue'

const edge = (displayName: string, otherKey: string) => ({
  type: displayName,
  inverse: displayName,
  direction: 'out',
  displayName,
  other: { id: `Team:${otherKey}`, type: 'Team', key: otherKey },
  props: {},
  provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
})

/**
 * The panel follows the node it is shown beside. When that node changes, what it showed for the
 * previous one must not come back: an answer for a node the panel has moved on from is dropped,
 * however late it arrives (#85, where following a link to a work item raced its change's edges).
 */
describe('RelationshipPanel', () => {
  it('shows the relationships of the node it is on now, not of one it was on before', async () => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/edges', async ({ request }) => {
        const nodeId = new URL(request.url).searchParams.get('nodeId')
        if (nodeId === 'Team:before') {
          await delay(50)
          return HttpResponse.json({ items: [] })
        }
        return HttpResponse.json({ items: [edge('OWNS', 'after-owned')] })
      })
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }]
    })
    const wrapper = mount(RelationshipPanel, {
      global: { plugins: [router] },
      props: { type: 'Team', nodeKey: 'before' }
    })

    await wrapper.setProps({ nodeKey: 'after' })
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 100))
    await flushPromises()

    const list = wrapper.find('[data-test="relationship-list"]')
    expect(list.text()).toContain('OWNS')
    expect(list.text()).toContain('after-owned')
  })

  it('marks a relationship a rule inferred, with the rule and its confidence, and no other', async () => {
    const inferred = {
      ...edge('OWNS_RESOURCE', 'billing-queue'),
      props: { rule: 'iac' },
      provenance: { sourceSystem: 'link-engine', confidence: 0.7, inferred: true }
    }
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/edges', () =>
        HttpResponse.json({ items: [inferred, edge('OWNS', 'stated-owned')] })
      )
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }]
    })
    const wrapper = mount(RelationshipPanel, {
      global: { plugins: [router] },
      props: { type: 'Repository', nodeKey: 'github.com/acme/billing' }
    })
    await flushPromises()

    const rows = wrapper.findAll('[data-test="relationship"]')
    const guessed = rows.find(row => row.text().includes('billing-queue'))!
    const stated = rows.find(row => row.text().includes('stated-owned'))!
    const badge = guessed.find('[data-test="inferred-badge"]')
    expect(badge.text()).toContain('iac')
    expect(badge.text()).toContain('70%')
    expect(stated.find('[data-test="inferred-badge"]').exists()).toBe(false)
  })
})
