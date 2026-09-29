import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { http, HttpResponse, delay } from 'msw'
import { server } from '@/test/msw/server'
import AppLayout from './AppLayout.vue'

/**
 * The shell draws its navigation from the ontology, so a node type added to the registry is
 * browsable without a frontend release, and the graph's own bookkeeping types stay out of it.
 */
const ontology = {
  version: '1.4.0',
  nodeTypes: [
    {
      name: 'Repository',
      description: null,
      identity: ['host', 'org', 'name'],
      meta: false,
      properties: []
    },
    { name: 'Team', description: null, identity: ['name'], meta: false, properties: [] },
    { name: 'SyncRun', description: null, identity: ['id'], meta: true, properties: [] }
  ],
  edgeTypes: []
}
const info = {
  deployment: {
    commit: '0123456789abcdef0123456789abcdef01234567',
    version: '0.0.1',
    ontologyVersion: '1.4.0',
    profile: 'docker',
    readOnly: false
  }
}

type Reply = () => Response | Promise<Response>

const serve = ({
  ontologyReply = () => HttpResponse.json(ontology),
  infoReply = () => HttpResponse.json(info)
}: { ontologyReply?: Reply; infoReply?: Reply } = {}) =>
  server.use(http.get('/api/v1/ontology', ontologyReply), http.get('/actuator/info', infoReply))

const mountLayout = async (path = '/') => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:any(.*)*', component: { template: '<p>page</p>' } }]
  })
  router.push(path)
  await router.isReady()
  return mount(AppLayout, { global: { plugins: [router] } })
}

const navLinks = (wrapper: Awaited<ReturnType<typeof mountLayout>>) =>
  wrapper
    .find('nav[aria-label="Node types"]')
    .findAll('a')
    .map(a => [a.text(), a.attributes('href')])

describe('AppLayout', () => {
  it('offers one link per node type the ontology declares', async () => {
    serve()
    const wrapper = await mountLayout()
    await flushPromises()

    expect(navLinks(wrapper)).toEqual([
      ['Repository', '/nodes/Repository'],
      ['Team', '/nodes/Team']
    ])
  })

  it('marks the type being viewed', async () => {
    serve()
    const wrapper = await mountLayout('/nodes/Team')
    await flushPromises()

    const current = wrapper.find('nav[aria-label="Node types"] [aria-current="page"]')
    expect(current.text()).toBe('Team')
  })

  it('leaves the meta types out', async () => {
    serve()
    const wrapper = await mountLayout()
    await flushPromises()

    expect(wrapper.find('nav[aria-label="Node types"]').text()).not.toContain('SyncRun')
  })

  it('keeps the connectors screen in the header', async () => {
    serve()
    const wrapper = await mountLayout()
    await flushPromises()

    expect(wrapper.find('header a[href="/connectors"]').text()).toBe('Connectors')
  })

  it('links to the sync run history from the header', async () => {
    serve()
    const wrapper = await mountLayout()
    await flushPromises()

    expect(wrapper.find('header a[href="/sync-runs"]').text()).toBe('Sync runs')
  })

  it('says it is loading until the ontology arrives', async () => {
    serve({
      ontologyReply: async () => {
        await delay('infinite')
        return HttpResponse.json(ontology)
      }
    })
    const wrapper = await mountLayout()

    expect(wrapper.find('nav[aria-label="Node types"]').attributes('aria-busy')).toBe('true')
  })

  it('says so when the ontology cannot be loaded, rather than showing an empty menu', async () => {
    serve({ ontologyReply: () => new HttpResponse(null, { status: 503 }) })
    const wrapper = await mountLayout()
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Could not load the node types')
  })

  it('names the ontology version and the short build in the footer', async () => {
    serve()
    const wrapper = await mountLayout()
    await flushPromises()

    const footer = wrapper.find('footer').text()
    expect(footer).toContain('ontology v1.4.0')
    expect(footer).toContain('build 0123456')
  })

  it('says the build is unknown when the image was not stamped or info cannot be read', async () => {
    serve({ infoReply: () => new HttpResponse(null, { status: 404 }) })
    const wrapper = await mountLayout()
    await flushPromises()

    expect(wrapper.find('footer').text()).toContain('build unknown')
  })
})
