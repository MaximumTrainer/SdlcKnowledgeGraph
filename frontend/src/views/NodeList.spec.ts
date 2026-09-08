import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeList from './NodeList.vue'

/**
 * The tabs are the registry's node types, not a hand-written list, which is what lets a type added
 * to the ontology become editable without a frontend release.
 */
const teams = [
  {
    id: 'Team:core',
    type: 'Team',
    key: 'core',
    props: { name: 'core' },
    provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
  },
  {
    id: 'Team:platform',
    type: 'Team',
    key: 'platform',
    props: { name: 'platform', email: 'platform@acme.example' },
    provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
  }
]

const router = (): Router =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/nodes/:type', name: 'nodes', component: { template: '<div />' } },
      { path: '/nodes/:type/new', name: 'node-new', component: { template: '<div />' } },
      { path: '/nodes/:type/:id', name: 'node', component: { template: '<div />' } }
    ]
  })

const listFor = async (type: string) => {
  const wrapper = mount(NodeList, { props: { type }, global: { plugins: [router()] } })
  await flushPromises()
  return wrapper
}

describe('NodeList', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Team', () => HttpResponse.json({ items: teams, nextCursor: null }))
    )
  })

  it('shows one tab per node type the ontology declares', async () => {
    const wrapper = await listFor('Team')

    const tabs = wrapper.findAll('[data-test="type-tab"]').map(tab => tab.text())

    expect(tabs).toEqual(ontologyFixture.nodeTypes.map(type => type.name))
  })

  it('marks the type being viewed', async () => {
    const wrapper = await listFor('Team')

    expect(wrapper.find('[data-test="type-tab"].active').text()).toBe('Team')
  })

  it('lists the nodes of that type by key', async () => {
    const wrapper = await listFor('Team')

    const keys = wrapper.findAll('[data-test="node-key"]').map(cell => cell.text())

    expect(keys).toEqual(['core', 'platform'])
  })

  it('shows the first three declared properties as columns', async () => {
    const wrapper = await listFor('Team')

    const headers = wrapper.findAll('th').map(header => header.text())

    expect(headers).toEqual(['key', 'name', 'email'])
  })

  it('offers a way to create one', async () => {
    const wrapper = await listFor('Team')

    expect(wrapper.find('[data-test="new-node"]').exists()).toBe(true)
  })

  it('says so plainly when a type has no nodes yet', async () => {
    server.use(http.get('/api/v1/nodes/Team', () => HttpResponse.json({ items: [] })))
    const wrapper = await listFor('Team')

    expect(wrapper.text()).toContain('No Team nodes yet')
  })
})
