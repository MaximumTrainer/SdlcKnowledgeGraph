import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter } from 'vue-router'
import { server } from '@/test/msw/server'
import {
  insufficientScope,
  providing,
  READ_ONLY,
  READ_WRITE,
  sessionWith
} from '@/test/authSession'
import CandidateLinksView from './CandidateLinksView.vue'

/**
 * The review of the link engine's work (#28, FR12). What is asserted is that a reviewer can read
 * each candidate - resource, repository, rule, confidence, evidence - narrow the list, decide on a
 * candidate and see it leave, and start a resolution; and that a reader is offered none of the
 * decisions the API would refuse them.
 */
const candidate = (id: string, overrides = {}) => ({
  id,
  resource: {
    key: `aws:arn:aws:sqs:eu-west-1:1:${id}-prod`,
    name: `${id}-prod`,
    provider: 'aws',
    accountId: '1'
  },
  repository: { key: `github.com/acme/${id}`, name: id },
  confidence: 0.4,
  rule: 'naming',
  evidence: { name: `${id}-prod`, normalised: id, matched: 'name' },
  status: 'pending',
  createdAt: '2026-09-30T12:00:00Z',
  ...overrides
})

const page = (items = [candidate('billing'), candidate('ledger')], overrides = {}) => ({
  items,
  page: 0,
  size: 50,
  totalElements: items.length,
  totalPages: 1,
  ...overrides
})

/** Every query the list was asked for, in order, so a test can say what a filter sent. */
let listed: URLSearchParams[]
let decided: string[]

const serve = (reply: (query: URLSearchParams) => Response = () => HttpResponse.json(page())) =>
  server.use(
    http.get('/api/v1/links/candidates', ({ request }) => {
      const query = new URL(request.url).searchParams
      listed.push(query)
      return reply(query)
    })
  )

const mountView = async (scopes: string[] | null = READ_WRITE) => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:any(.*)*', component: { template: '<div />' } }]
  })
  const wrapper = mount(CandidateLinksView, {
    attachTo: document.body,
    global: {
      plugins: [router],
      provide: providing(scopes ? sessionWith(scopes) : null)
    }
  })
  await flushPromises()
  return wrapper
}

describe('CandidateLinksView', () => {
  beforeEach(() => {
    listed = []
    decided = []
    document.body.innerHTML = ''
    serve()
    server.use(
      http.post('/api/v1/links/candidates/:id/:decision', ({ params }) => {
        decided.push(`${String(params.decision)} ${String(params.id)}`)
        return HttpResponse.json({})
      })
    )
  })

  it('lists each candidate with its resource, repository, rule, confidence and status', async () => {
    const wrapper = await mountView()

    const rows = wrapper.findAll('[data-test="candidate"]')
    expect(rows).toHaveLength(2)
    const row = rows[0]
    expect(row.text()).toContain('billing-prod')
    expect(row.text()).toContain('aws')
    expect(row.text()).toContain('github.com/acme/billing')
    expect(row.find('[data-test="rule-badge"]').text()).toBe('naming')
    expect(row.find('[data-test="confidence-bar"]').attributes('aria-valuenow')).toBe('40')
    expect(row.find('[data-test="candidate-status"]').text()).toBe('pending')
  })

  it('asks for the open candidates, fifty at a time, unfiltered', async () => {
    await mountView()

    expect(Object.fromEntries(listed[0])).toEqual({ page: '0', size: '50' })
  })

  it('sends the status, provider, confidence and search filters', async () => {
    const wrapper = await mountView()

    await wrapper.find('select#filter-status').setValue('conflict')
    await wrapper.find('select#filter-provider').setValue('azure')
    await wrapper.find('input#filter-min-confidence').setValue('0.5')
    await wrapper.find('input#filter-search').setValue('pay')
    await flushPromises()

    expect(Object.fromEntries(listed.at(-1)!)).toEqual({
      status: 'conflict',
      provider: 'azure',
      minConfidence: '0.5',
      q: 'pay',
      page: '0',
      size: '50'
    })
  })

  it('shows a candidate evidence when asked', async () => {
    const wrapper = await mountView()
    const row = wrapper.findAll('[data-test="candidate"]')[0]
    expect(wrapper.find('[data-test="evidence-list"]').exists()).toBe(false)

    const toggle = row.find('button[data-test="evidence-toggle"]')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    const evidence = wrapper.find('[data-test="evidence-list"]')
    expect(evidence.text()).toContain('normalised')
    expect(evidence.text()).toContain('billing-prod')
  })

  it('accepts a candidate and lists the candidates again without it', async () => {
    const wrapper = await mountView()
    serve(() => HttpResponse.json(page([candidate('ledger')])))

    await wrapper
      .findAll('[data-test="candidate"]')[0]
      .find('[data-test="accept"]')
      .trigger('click')
    await flushPromises()

    expect(decided).toEqual(['accept billing'])
    expect(wrapper.findAll('[data-test="candidate"]')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('billing-prod')
  })

  it('rejects a candidate', async () => {
    const wrapper = await mountView()

    await wrapper
      .findAll('[data-test="candidate"]')[1]
      .find('[data-test="reject"]')
      .trigger('click')
    await flushPromises()

    expect(decided).toEqual(['reject ledger'])
  })

  it('offers no decision on a candidate already decided', async () => {
    serve(() =>
      HttpResponse.json(page([candidate('billing', { status: 'rejected', rejectedBy: 'dan' })]))
    )
    const wrapper = await mountView()

    const row = wrapper.find('[data-test="candidate"]')
    expect(row.find('[data-test="accept"]').exists()).toBe(false)
    expect(row.find('[data-test="reject"]').exists()).toBe(false)
    expect(row.text()).toContain('dan')
  })

  it('offers a reader, and a deployment with no login, no decision and no resolution', async () => {
    for (const scopes of [READ_ONLY, null]) {
      const wrapper = await mountView(scopes)

      expect(wrapper.findAll('[data-test="candidate"]')).toHaveLength(2)
      expect(wrapper.find('[data-test="accept"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="reject"]').exists()).toBe(false)
      expect(wrapper.find('[data-test="run-resolution"]').exists()).toBe(false)
      wrapper.unmount()
    }
  })

  it('says why a decision was refused', async () => {
    server.use(
      http.post('/api/v1/links/candidates/:id/accept', () =>
        HttpResponse.json(insufficientScope, { status: 403 })
      )
    )
    const wrapper = await mountView()

    await wrapper
      .findAll('[data-test="candidate"]')[0]
      .find('[data-test="accept"]')
      .trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toMatch(/graph:write/)
  })

  it('runs a resolution, waits for its run to finish and lists what it found', async () => {
    let polled = 0
    server.use(
      http.post('/api/v1/links/resolve', () =>
        HttpResponse.json({ syncRunId: 'links-1', mode: 'FULL' }, { status: 202 })
      ),
      http.get('/api/v1/sync-runs/links-1', () => {
        polled++
        return HttpResponse.json({ id: 'links-1', status: polled > 1 ? 'SUCCESS' : 'RUNNING' })
      })
    )
    const wrapper = await mountView()
    serve(() => HttpResponse.json(page([candidate('orders')])))

    await wrapper.find('[data-test="run-resolution"]').trigger('click')
    await expect
      .poll(
        async () => {
          await flushPromises()
          return wrapper.findAll('[data-test="candidate"]').length
        },
        { timeout: 5000 }
      )
      .toBe(1)

    expect(wrapper.text()).toContain('orders-prod')
    expect(wrapper.find('[data-test="resolution-status"]').text()).toMatch(/SUCCESS/)
  })

  it('says when nothing matches', async () => {
    serve(() => HttpResponse.json(page([])))
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="no-candidates"]').exists()).toBe(true)
  })
})
