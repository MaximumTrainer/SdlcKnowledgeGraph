import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeDetail from './NodeDetail.vue'
import {
  insufficientScope,
  providing,
  READ_ONLY,
  READ_WRITE,
  sessionWith
} from '@/test/authSession'

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
  provenance: {
    sourceSystem: 'manual',
    confidence: 1,
    inferred: false,
    writtenBy: 'dan',
    principalType: 'user'
  }
}

/** A team a registered agent created (#115): a service, acting for the team that owns it. */
const byAgent = {
  id: 'Team:incident-review',
  type: 'Team',
  key: 'incident-review',
  props: { name: 'incident-review' },
  provenance: {
    sourceSystem: 'manual',
    confidence: 1,
    inferred: false,
    writtenBy: 'triage-agent',
    principalType: 'service',
    onBehalfOfTeam: 'team-payments'
  }
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
      http.get('/api/v1/nodes/Team/incident-review', () => HttpResponse.json(byAgent)),
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

  /** Who stated a fact is part of its provenance (#114, FR-3). */
  it('says who wrote the node, and what kind of principal they were', async () => {
    const wrapper = await mountDetail('Team', 'platform')

    expect(wrapper.find('[data-test="provenance-written-by"]').text()).toBe('dan')
    expect(wrapper.find('[data-test="provenance-principal-type"]').text()).toBe('user')
  })

  /** A service principal writes for a team, and says which (#115, FR-3). */
  it('names the team a service principal wrote for', async () => {
    const wrapper = await mountDetail('Team', 'incident-review')

    expect(wrapper.find('[data-test="provenance-written-by"]').text()).toBe('triage-agent')
    expect(wrapper.find('[data-test="provenance-principal-type"]').text()).toBe('service')
    expect(wrapper.find('[data-test="provenance-on-behalf-of-team"]').text()).toBe('team-payments')
  })

  it('shows no team for a user, who writes for themselves', async () => {
    const wrapper = await mountDetail('Team', 'platform')

    expect(wrapper.find('[data-test="provenance-on-behalf-of-team"]').exists()).toBe(false)
  })

  it('says so when a fact predates recording who wrote it', async () => {
    const wrapper = await mountDetail('Repository', 'github.com/acme/payments')

    expect(wrapper.find('[data-test="provenance-written-by"]').text()).toBe('not recorded')
  })
})

/** What a user may change is what their token's scopes allow (#116). */
describe('NodeDetail, for a user who may only read', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/nodes/Team/platform', () => HttpResponse.json(team)),
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/edges', () => HttpResponse.json({ items: [] }))
    )
  })

  const detailAs = async (scopes: string[]) => {
    const r = router()
    await r.push('/nodes/Team/platform')
    await r.isReady()
    const wrapper = mount(NodeDetail, {
      global: { plugins: [r], provide: providing(sessionWith(scopes)) },
      props: { type: 'Team', id: 'platform' }
    })
    await flushPromises()
    return wrapper
  }

  it('shows the node but offers no way to edit, delete or relate it', async () => {
    const wrapper = await detailAs(READ_ONLY)

    expect(wrapper.find('h1').text()).toBe('platform')
    expect(wrapper.find('[data-test="edit-node"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="delete-node"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="add-relationship"]').exists()).toBe(false)
  })

  it('offers them to a user who may write', async () => {
    const wrapper = await detailAs(READ_WRITE)

    expect(wrapper.find('[data-test="edit-node"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="delete-node"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="add-relationship"]').exists()).toBe(true)
  })

  it('says which scope was missing when a delete is refused for it', async () => {
    server.use(
      http.delete('/api/v1/nodes/Team/platform', () =>
        HttpResponse.json(insufficientScope, { status: 403 })
      )
    )
    const wrapper = await detailAs(READ_WRITE)

    await wrapper.find('[data-test="delete-node"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('.error').text()).toBe(
      'You do not have permission to do that: it needs graph:write, and you hold graph:read.'
    )
  })
})
