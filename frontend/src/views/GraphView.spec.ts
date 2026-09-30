import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { fakeCytoscape } from '@/test/fakeCytoscape'
import type { Ontology, Subgraph, SubgraphNode } from '@/services/api'
import GraphView from './GraphView.vue'

vi.mock('cytoscape', async () => ({
  default: (await import('@/test/fakeCytoscape')).fakeCytoscape.factory
}))
vi.mock('cytoscape-dagre', () => ({ default: { name: 'dagre' } }))

/**
 * The graph view (#9) with Cytoscape faked: what it asks the API for, what it hands the canvas, and
 * what it does when a node is tapped. How the canvas looks is the browser suite's to check.
 */
const PAYMENTS = 'Repository:github.com/acme/payments'
const SHARED = 'Repository:github.com/acme/shared-lib'
const CHECKOUT = 'Repository:github.com/acme/checkout'
const TEAM = 'Team:platform'
const BUCKET = 'CloudResource:aws:arn:aws:s3:::acme-logs'

const provenance = {
  sourceSystem: 'manual',
  confidence: 1,
  inferred: false
} as SubgraphNode['provenance']

const node = (id: string, label: string, distance = 1): SubgraphNode => ({
  id,
  type: id.split(':')[0],
  key: id.slice(id.indexOf(':') + 1),
  label,
  distance,
  props: { name: label },
  provenance
})

const neighbourhoodOfPayments: Subgraph = {
  root: PAYMENTS,
  truncated: false,
  nodes: [
    node(PAYMENTS, 'payments', 0),
    node(SHARED, 'shared-lib'),
    node(TEAM, 'platform'),
    node(BUCKET, 'acme-logs')
  ],
  edges: [
    {
      id: `DEPENDS_ON:${PAYMENTS}>${SHARED}`,
      type: 'DEPENDS_ON',
      inverse: 'DEPENDED_ON_BY',
      from: PAYMENTS,
      to: SHARED,
      confidence: 1,
      inferred: false
    },
    {
      id: `OWNED_BY:${PAYMENTS}>${TEAM}`,
      type: 'OWNED_BY',
      inverse: 'OWNS',
      from: PAYMENTS,
      to: TEAM,
      confidence: 1,
      inferred: false
    },
    {
      id: `OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`,
      type: 'OWNS_RESOURCE',
      inverse: 'OWNED_BY_REPO',
      from: PAYMENTS,
      to: BUCKET,
      confidence: 0.7,
      inferred: true
    }
  ]
}

const neighbourhoodOfShared: Subgraph = {
  root: SHARED,
  truncated: false,
  nodes: [node(SHARED, 'shared-lib', 0), node(PAYMENTS, 'payments'), node(CHECKOUT, 'checkout')],
  edges: [
    {
      id: `DEPENDS_ON:${PAYMENTS}>${SHARED}`,
      type: 'DEPENDS_ON',
      inverse: 'DEPENDED_ON_BY',
      from: PAYMENTS,
      to: SHARED,
      confidence: 1,
      inferred: false
    },
    {
      id: `DEPENDS_ON:${CHECKOUT}>${SHARED}`,
      type: 'DEPENDS_ON',
      inverse: 'DEPENDED_ON_BY',
      from: CHECKOUT,
      to: SHARED,
      confidence: 1,
      inferred: false
    }
  ]
}

const ontology: Ontology = {
  version: '1.0.0',
  nodeTypes: [
    {
      name: 'Repository',
      description: null,
      identity: ['host', 'org', 'name'],
      properties: [],
      meta: false,
      displayProperty: 'name'
    },
    {
      name: 'Team',
      description: null,
      identity: ['name'],
      properties: [],
      meta: false,
      displayProperty: 'name'
    },
    {
      name: 'CloudResource',
      description: null,
      identity: ['provider', 'resourceId'],
      properties: [],
      meta: false,
      displayProperty: 'name'
    },
    {
      name: 'SyncRun',
      description: null,
      identity: ['id'],
      properties: [],
      meta: true,
      displayProperty: 'id'
    }
  ],
  edgeTypes: [
    {
      name: 'OWNED_BY',
      description: null,
      from: ['Repository'],
      to: ['Team'],
      inverse: 'OWNS',
      properties: []
    },
    {
      name: 'DEPENDS_ON',
      description: null,
      from: ['Repository'],
      to: ['Repository'],
      inverse: 'DEPENDED_ON_BY',
      properties: []
    },
    {
      name: 'OWNS_RESOURCE',
      description: null,
      from: ['Repository'],
      to: ['CloudResource'],
      inverse: 'OWNED_BY_REPO',
      properties: []
    }
  ]
}

const impactOfShared = {
  root: { id: SHARED, type: 'Repository', key: 'github.com/acme/shared-lib' },
  depth: 1,
  direction: 'downstream',
  minConfidence: 0.5,
  truncated: false,
  affected: [
    {
      node: {
        id: PAYMENTS,
        type: 'Repository',
        key: 'github.com/acme/payments',
        props: { name: 'payments' }
      },
      distance: 1,
      confidence: 1,
      inferred: false,
      path: [{ edge: 'DEPENDED_ON_BY', from: SHARED, to: PAYMENTS, confidence: 1, inferred: false }]
    }
  ],
  byType: { Repository: 1, excluded: 0 }
}

let requests: URL[] = []
let impactRequests: URL[] = []

const serve = (
  answers: Record<string, Subgraph> = {
    [PAYMENTS]: neighbourhoodOfPayments,
    [SHARED]: neighbourhoodOfShared
  }
) =>
  server.use(
    http.get('/api/v1/ontology', () => HttpResponse.json(ontology)),
    http.get('/api/v1/graph/neighbourhood', ({ request }) => {
      const url = new URL(request.url)
      requests.push(url)
      const answer = answers[url.searchParams.get('nodeId') ?? '']
      return answer
        ? HttpResponse.json(answer)
        : HttpResponse.json({ error: 'node not found' }, { status: 404 })
    }),
    http.get('/api/v1/graph/impact', ({ request }) => {
      impactRequests.push(new URL(request.url))
      return HttpResponse.json(impactOfShared)
    })
  )

const makeRouter = (): Router =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/graph', component: GraphView },
      {
        path: '/graph/:nodeId+',
        component: GraphView,
        props: route => ({ nodeId: ([] as string[]).concat(route.params.nodeId).join('/') })
      },
      { path: '/nodes/:type/:id+', component: { template: '<div />' } }
    ]
  })

let wrapper: VueWrapper | null = null

const open = async (path = `/graph/${encodeURIComponent(PAYMENTS)}`) => {
  const router = makeRouter()
  await router.push(path)
  await router.isReady()
  const App = { template: '<router-view />' }
  wrapper = mount(App, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { router, wrapper }
}

describe('GraphView', () => {
  beforeEach(() => {
    requests = []
    impactRequests = []
    fakeCytoscape.reset()
    window.__cy = undefined
    window.__cyReady = undefined
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('asks for the depth-1 neighbourhood of the node in the path, in both directions', async () => {
    serve()
    await open()

    expect(requests).toHaveLength(1)
    expect(requests[0].searchParams.get('nodeId')).toBe(PAYMENTS)
    expect(requests[0].searchParams.get('depth')).toBe('1')
    expect(requests[0].searchParams.get('direction')).toBe('both')
    expect(requests[0].searchParams.has('nodeTypes')).toBe(false)
  })

  it('draws every node and edge, laid out left to right with dagre', async () => {
    serve()
    await open()

    const cy = fakeCytoscape.last()
    expect(cy.nodeIds()).toEqual([PAYMENTS, SHARED, TEAM, BUCKET])
    expect(cy.edges().length).toBe(3)
    expect(cy.layouts.at(-1)).toMatchObject({ name: 'dagre', rankDir: 'LR' })
    expect(fakeCytoscape.extensions).toContainEqual({ name: 'dagre' })
  })

  it('hands the canvas an inferred edge marked as one, and a legend saying what dashed means', async () => {
    serve()
    const { wrapper } = await open()

    const cy = fakeCytoscape.last()
    expect(cy.edges('[inferred]').elements.map(edge => edge.data.id)).toEqual([
      `OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`
    ])
    expect(wrapper.get('[data-test="graph-legend"]').text()).toMatch(/dashed/i)
    expect(wrapper.get('[data-test="graph-legend"]').text()).toMatch(/inferred/i)
  })

  it('exposes the canvas to the browser tests in test mode, and says when the layout has stopped', async () => {
    serve()
    await open()

    expect(window.__cy).toBe(fakeCytoscape.last())
    expect(window.__cyReady).toBe(true)
  })

  it('unticking a node type puts the filter in the query string and fetches again', async () => {
    serve()
    const { router, wrapper } = await open(`/graph/${encodeURIComponent(PAYMENTS)}?e2e=1`)

    const types = wrapper.get('[data-test="node-type-filter"]')
    expect(
      types.findAll('input[type="checkbox"]').map(box => (box.element as HTMLInputElement).checked)
    ).toEqual([true, true, true])
    // Meta types are not offered.
    expect(types.text()).not.toContain('SyncRun')

    await types.get('input[value="CloudResource"]').setValue(false)
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({
      nodeTypes: 'Repository,Team',
      e2e: '1'
    })
    expect(requests.at(-1)?.searchParams.get('nodeTypes')).toBe('Repository,Team')
  })

  it('an edge type and the direction are filters too', async () => {
    serve()
    const { router, wrapper } = await open()

    await wrapper.get('[data-test="edge-type-filter"] input[value="OWNED_BY"]').setValue(false)
    await flushPromises()
    await wrapper.get('select[data-test="direction"]').setValue('out')
    await flushPromises()

    expect(router.currentRoute.value.query).toMatchObject({
      edgeTypes: 'DEPENDS_ON,OWNS_RESOURCE',
      direction: 'out'
    })
    expect(requests.at(-1)?.searchParams.get('direction')).toBe('out')
    expect(requests.at(-1)?.searchParams.get('edgeTypes')).toBe('DEPENDS_ON,OWNS_RESOURCE')
  })

  it('moving the depth slider fetches the deeper neighbourhood', async () => {
    serve()
    const { router, wrapper } = await open()

    const slider = wrapper.get('input[type="range"]')
    expect(slider.attributes()).toMatchObject({ min: '1', max: '3' })
    await slider.setValue('2')
    await slider.trigger('change')
    await flushPromises()

    expect(router.currentRoute.value.query.depth).toBe('2')
    expect(requests.at(-1)?.searchParams.get('depth')).toBe('2')
  })

  it('reads its filters from the link it was opened with', async () => {
    serve()
    const { wrapper } = await open(
      `/graph/${encodeURIComponent(PAYMENTS)}?depth=3&nodeTypes=Repository&direction=in`
    )

    expect(requests[0].searchParams.get('depth')).toBe('3')
    expect(requests[0].searchParams.get('nodeTypes')).toBe('Repository')
    expect(requests[0].searchParams.get('direction')).toBe('in')
    expect((wrapper.get('input[type="range"]').element as HTMLInputElement).value).toBe('3')
  })

  it('tapping a node opens its drawer, with its key, properties and provenance', async () => {
    serve()
    const { wrapper } = await open()

    expect(wrapper.find('[data-test="node-drawer"]').exists()).toBe(false)
    fakeCytoscape.last().trigger('tap', SHARED)
    await flushPromises()

    const drawer = wrapper.get('[data-test="node-drawer"]')
    expect(drawer.text()).toContain('github.com/acme/shared-lib')
    expect(drawer.text()).toContain('shared-lib')
    expect(drawer.get('[data-test="drawer-source-system"]').text()).toBe('manual')
    expect(drawer.get('a[data-test="drawer-open"]').attributes('href')).toBe(
      '/nodes/Repository/github.com/acme/shared-lib'
    )
  })

  it('expanding a node merges its neighbourhood without moving or repeating what is drawn', async () => {
    serve()
    const { wrapper } = await open()
    const cy = fakeCytoscape.last()
    cy.getElementById(SHARED).position({ x: 100, y: 40 })
    const layoutsBefore = cy.layouts.length

    cy.trigger('tap', SHARED)
    await flushPromises()
    await wrapper
      .get('[data-test="node-drawer"]')
      .get('button[data-test="drawer-expand"]')
      .trigger('click')
    await flushPromises()

    expect(requests.at(-1)?.searchParams.get('nodeId')).toBe(SHARED)
    expect(requests.at(-1)?.searchParams.get('depth')).toBe('1')
    expect(cy.nodeIds()).toEqual([PAYMENTS, SHARED, TEAM, BUCKET, CHECKOUT])
    expect(cy.edges().length).toBe(4)
    // Nothing already placed is laid out again, and the new node is placed beside the one expanded.
    expect(cy.layouts.length).toBe(layoutsBefore)
    expect(cy.getElementById(SHARED).position()).toEqual({ x: 100, y: 40 })
    expect(cy.getElementById(CHECKOUT).position()).not.toEqual({ x: 0, y: 0 })
    expect(window.__cyReady).toBe(true)
  })

  it('double-tapping a node expands it', async () => {
    serve()
    await open()

    fakeCytoscape.last().trigger('dbltap', SHARED)
    await flushPromises()

    expect(requests.at(-1)?.searchParams.get('nodeId')).toBe(SHARED)
    expect(fakeCytoscape.last().nodeIds()).toContain(CHECKOUT)
  })

  it('the blast radius of the selected node marks what it reaches and counts it by type', async () => {
    serve()
    const { wrapper } = await open()
    const cy = fakeCytoscape.last()
    cy.trigger('tap', SHARED)
    await flushPromises()

    await wrapper.get('input[data-test="blast-radius"]').setValue(true)
    await flushPromises()

    expect(impactRequests).toHaveLength(1)
    expect(impactRequests[0].searchParams.get('nodeId')).toBe(SHARED)
    expect(impactRequests[0].searchParams.get('depth')).toBe('1')
    expect(impactRequests[0].searchParams.get('minConfidence')).toBe('0.5')
    expect(cy.getElementById(PAYMENTS).hasClass('affected')).toBe(true)
    expect(cy.getElementById(TEAM).hasClass('dimmed')).toBe(true)
    expect(cy.getElementById(SHARED).hasClass('dimmed')).toBe(false)
    expect(wrapper.get('[data-test="blast-radius-badge"]').text()).toContain('Repository: 1')

    await wrapper.get('input[data-test="blast-radius"]').setValue(false)
    await flushPromises()

    expect(cy.getElementById(PAYMENTS).hasClass('affected')).toBe(false)
    expect(cy.getElementById(TEAM).hasClass('dimmed')).toBe(false)
    expect(wrapper.find('[data-test="blast-radius-badge"]').exists()).toBe(false)
  })

  it('with nothing selected the blast radius is the root node’s', async () => {
    serve()
    const { wrapper } = await open()

    await wrapper.get('input[data-test="blast-radius"]').setValue(true)
    await flushPromises()

    expect(impactRequests[0].searchParams.get('nodeId')).toBe(PAYMENTS)
  })

  it('says so when the neighbourhood was cut short', async () => {
    serve({ [PAYMENTS]: { ...neighbourhoodOfPayments, truncated: true } })
    const { wrapper } = await open()

    expect(wrapper.get('[role="status"][data-test="truncated"]').text()).toBe(
      'Showing 500 of more nodes; narrow the filters'
    )
  })

  it('says so when the node does not exist', async () => {
    serve({})
    const { wrapper } = await open(
      `/graph/${encodeURIComponent('Repository:github.com/acme/nothing')}`
    )

    expect(wrapper.text()).toContain('That node does not exist.')
  })

  it('the fit button fits the drawing to the canvas', async () => {
    serve()
    const { wrapper } = await open()
    const fits = fakeCytoscape.last().fits

    await wrapper.get('button[data-test="fit"]').trigger('click')

    expect(fakeCytoscape.last().fits).toBe(fits + 1)
  })

  it('with no node in the path, asks which node to start from', async () => {
    serve()
    const { router, wrapper } = await open('/graph')

    expect(requests).toHaveLength(0)
    await wrapper.get('select[data-test="start-type"]').setValue('Team')
    await wrapper.get('input[data-test="start-key"]').setValue('platform')
    await wrapper.get('form[data-test="start"]').trigger('submit')
    await flushPromises()

    expect(router.currentRoute.value.params.nodeId).toEqual(['Team:platform'])
  })

  it('lets the canvas go when it is left', async () => {
    serve()
    const { wrapper } = await open()
    const cy = fakeCytoscape.last()

    wrapper.unmount()

    expect(cy.destroyed).toBe(true)
  })
})
