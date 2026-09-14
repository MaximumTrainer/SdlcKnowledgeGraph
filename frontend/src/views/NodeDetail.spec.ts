import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeDetail from './NodeDetail.vue'

/**
 * A Repository node is a pointer at something that exists elsewhere, and the first thing anyone
 * wants from it is to go and look at the real thing. The link is built from the stored canonical
 * url, so it cannot point somewhere the graph does not claim (#8).
 */
const repository = (host: string) => ({
  id: `Repository:${host}/acme/payments`,
  type: 'Repository',
  key: `${host}/acme/payments`,
  props: {
    url: `https://${host}/acme/payments`,
    host,
    org: 'acme',
    name: 'payments',
    defaultBranch: 'main'
  },
  provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
})

const team = {
  id: 'Team:platform',
  type: 'Team',
  key: 'platform',
  props: { name: 'platform' },
  provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
}

const router = (): Router =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/nodes/:type', name: 'nodes', component: { template: '<div />' } },
      { path: '/nodes/:type/:id', name: 'node', component: { template: '<div />' } },
      { path: '/nodes/:type/:id/edit', name: 'edit', component: { template: '<div />' } }
    ]
  })

const mountDetail = async (type: string, id: string) => {
  const r = router()
  await r.push(`/nodes/${type}/${id}`)
  await r.isReady()
  const wrapper = mount(NodeDetail, { global: { plugins: [r] }, props: { type, id } })
  await flushPromises()
  return wrapper
}

describe('NodeDetail', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/nodes/Repository/github.com/acme/payments', () =>
        HttpResponse.json(repository('github.com'))
      ),
      http.get('/api/v1/nodes/Repository/gitlab.com/acme/payments', () =>
        HttpResponse.json(repository('gitlab.com'))
      ),
      http.get('/api/v1/nodes/Team/platform', () => HttpResponse.json(team)),
      // RelationshipPanel loads both of these for every node it is shown beside.
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/edges', () => HttpResponse.json({ items: [] }))
    )
  })

  it('links out to the repository, named after the host it is on', async () => {
    const wrapper = await mountDetail('Repository', 'github.com/acme/payments')

    const link = wrapper.find('[data-test="open-remote"]')
    expect(link.attributes('href')).toBe('https://github.com/acme/payments')
    expect(link.text()).toBe('Open in github.com')
  })

  /** The label follows the stored host, so a GitLab repository does not claim to be on GitHub. */
  it('names a host other than GitHub correctly', async () => {
    const wrapper = await mountDetail('Repository', 'gitlab.com/acme/payments')

    expect(wrapper.find('[data-test="open-remote"]').text()).toBe('Open in gitlab.com')
  })

  it('opens in a new tab without handing the target a window reference', async () => {
    const wrapper = await mountDetail('Repository', 'github.com/acme/payments')

    const link = wrapper.find('[data-test="open-remote"]')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
  })

  it('shows no link on a node type that has no remote', async () => {
    const wrapper = await mountDetail('Team', 'platform')

    expect(wrapper.find('[data-test="open-remote"]').exists()).toBe(false)
  })
})
