import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeList from './NodeList.vue'
import { providing, READ_ONLY, READ_WRITE, sessionWith } from '@/test/authSession'

/**
 * The columns are the type's declared properties, not a hand-written list, which is what lets a type
 * added to the ontology become listable without a frontend release.
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

  /** How far behind each source is, where a reader lands (#93, FR-6). */
  it('warns of a source behind its freshness window', async () => {
    server.use(
      http.get('/api/v1/freshness', () =>
        HttpResponse.json({
          sources: [
            {
              source: 'aws',
              window: 'PT6H',
              windowSeconds: 21600,
              lastSuccessAt: '2026-09-29T06:00:00Z',
              lagSeconds: 108000,
              lagging: true
            }
          ]
        })
      )
    )
    const wrapper = await listFor('Team')

    expect(wrapper.find('[data-test="source-lag-aws"]').text()).toMatch(/behind/i)
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

/** What a user may change is what their token's scopes allow (#116). */
describe('NodeList, for a user who may only read', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Team', () => HttpResponse.json({ items: teams, nextCursor: null }))
    )
  })

  const listAs = async (scopes: string[]) => {
    const wrapper = mount(NodeList, {
      props: { type: 'Team' },
      global: { plugins: [router()], provide: providing(sessionWith(scopes)) }
    })
    await flushPromises()
    return wrapper
  }

  it('offers no way to create a node', async () => {
    const wrapper = await listAs(READ_ONLY)

    expect(wrapper.findAll('[data-test="node-key"]')).toHaveLength(2)
    expect(wrapper.find('[data-test="new-node"]').exists()).toBe(false)
  })

  it('offers it to a user who may write', async () => {
    const wrapper = await listAs(READ_WRITE)

    expect(wrapper.find('[data-test="new-node"]').exists()).toBe(true)
  })
})

/**
 * A deployment with no login is the anonymous read-only mode (#118): the API serves reads to anyone
 * and refuses every write, so the list offers nobody a way to create a node.
 */
describe('NodeList, on a deployment with no login', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Team', () => HttpResponse.json({ items: teams, nextCursor: null }))
    )
  })

  it('lists the nodes and offers no way to create one', async () => {
    const wrapper = mount(NodeList, {
      props: { type: 'Team' },
      global: { plugins: [router()], provide: providing(null) }
    })
    await flushPromises()

    expect(wrapper.findAll('[data-test="node-key"]')).toHaveLength(2)
    expect(wrapper.find('[data-test="new-node"]').exists()).toBe(false)
  })
})
