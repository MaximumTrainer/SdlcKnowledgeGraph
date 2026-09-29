import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import SyncRunsView from './SyncRunsView.vue'

/**
 * The run history answers "what did the connectors do, and which run went wrong" without reading
 * logs (#29, FR8). So what is asserted is that each run's outcome, size and cost can be read off a
 * row, that the filters reach the API, and that a row leads to the whole run.
 */
const run = (id: string, overrides = {}) => ({
  id,
  connector: 'github',
  sourceSystem: 'github',
  mode: 'FULL',
  status: 'SUCCESS',
  startedAt: '2026-09-01T10:00:00Z',
  finishedAt: '2026-09-01T10:01:30Z',
  durationMs: 90_000,
  nodesUpserted: 3,
  edgesUpserted: 2,
  tombstones: 1,
  error: null,
  ...overrides
})

const runs = [
  run('run-2', { status: 'PARTIAL', error: 'page 2 failed: the source answered 502' }),
  run('run-1', { connector: 'servicenow', mode: 'INCREMENTAL' })
]

const page = (items = runs, overrides = {}) => ({
  items,
  page: 0,
  size: 20,
  totalElements: items.length,
  totalPages: 1,
  ...overrides
})

const info = (readOnly: boolean) => ({
  deployment: {
    commit: 'unknown',
    version: '0.0.1',
    ontologyVersion: '1.0.0',
    profile: 'docker',
    readOnly
  }
})

/** Every query the list was asked for, in order, so a test can say what a filter sent. */
let listed: URLSearchParams[]

const serve = (reply: (query: URLSearchParams) => Response = () => HttpResponse.json(page())) =>
  server.use(
    http.get('/api/v1/sync-runs', ({ request }) => {
      const query = new URL(request.url).searchParams
      listed.push(query)
      return reply(query)
    })
  )

const mountView = async () => {
  const wrapper = mount(SyncRunsView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('SyncRunsView', () => {
  beforeEach(() => {
    listed = []
    document.body.innerHTML = ''
    server.use(
      http.get('/actuator/info', () => HttpResponse.json(info(false))),
      http.get('/api/v1/connectors', () =>
        HttpResponse.json([{ name: 'github' }, { name: 'servicenow' }])
      )
    )
    serve()
  })

  it('lists each run with its connector, mode, status, start, duration and counts', async () => {
    const wrapper = await mountView()

    const row = wrapper.find('[data-test="run-run-2"]')
    expect(row.text()).toContain('github')
    expect(row.text()).toContain('FULL')
    expect(row.find('[data-test="status-chip"]').text()).toBe('PARTIAL')
    expect(row.text()).toContain('2026-09-01 10:00:00 UTC')
    expect(row.text()).toContain('1 m 30 s')
    expect(row.find('[data-test="counts"]').text()).toBe('3 / 2 / 1')
    expect(row.text()).toContain('page 2 failed')
  })

  it('keeps the newest run first, as the API sends them', async () => {
    const wrapper = await mountView()

    const ids = wrapper.findAll('tbody tr').map(row => row.attributes('data-test'))
    expect(ids).toEqual(['run-run-2', 'run-run-1'])
  })

  it('asks for the first page, twenty runs at a time, with no filters', async () => {
    await mountView()

    expect(Object.fromEntries(listed[0])).toEqual({ page: '0', size: '20' })
  })

  it('sends the connector, status and time window filters, times as UTC instants', async () => {
    const wrapper = await mountView()

    await wrapper.find('select#filter-connector').setValue('github')
    await wrapper.find('select#filter-status').setValue('FAILED')
    await wrapper.find('input#filter-from').setValue('2026-09-01T00:00')
    await wrapper.find('input#filter-to').setValue('2026-09-02T00:00')
    await flushPromises()

    expect(Object.fromEntries(listed.at(-1)!)).toEqual({
      connector: 'github',
      status: 'FAILED',
      from: '2026-09-01T00:00:00Z',
      to: '2026-09-02T00:00:00Z',
      page: '0',
      size: '20'
    })
  })

  it('offers the registered connectors to filter by', async () => {
    const wrapper = await mountView()

    const options = wrapper.findAll('select#filter-connector option').map(o => o.text())
    expect(options).toEqual(['All connectors', 'github', 'servicenow'])
  })

  it('pages through the history, and says where it is', async () => {
    serve(query =>
      HttpResponse.json(
        page(runs, { page: Number(query.get('page')), totalElements: 45, totalPages: 3 })
      )
    )
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="page-status"]').text()).toBe('Page 1 of 3 · 45 runs')
    expect(wrapper.find('[data-test="previous-page"]').attributes('disabled')).toBeDefined()

    await wrapper.find('[data-test="next-page"]').trigger('click')
    await flushPromises()

    expect(listed.at(-1)!.get('page')).toBe('1')
    expect(wrapper.find('[data-test="page-status"]').text()).toBe('Page 2 of 3 · 45 runs')
  })

  it('goes back to the first page when a filter changes', async () => {
    serve(query =>
      HttpResponse.json(
        page(runs, { page: Number(query.get('page')), totalElements: 45, totalPages: 3 })
      )
    )
    const wrapper = await mountView()
    await wrapper.find('[data-test="next-page"]').trigger('click')
    await flushPromises()

    await wrapper.find('select#filter-status').setValue('FAILED')
    await flushPromises()

    expect(listed.at(-1)!.get('page')).toBe('0')
  })

  it('says so when no run matches, rather than showing an empty table', async () => {
    serve(() => HttpResponse.json(page([])))
    const wrapper = await mountView()

    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.find('[data-test="no-runs"]').text()).toContain('No sync runs')
  })

  it('shows the reason the API gives for refusing a filter', async () => {
    serve(() =>
      HttpResponse.json(
        { error: 'invalid request', detail: 'from must be before to' },
        { status: 400 }
      )
    )
    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('from must be before to')
  })

  it('says the history could not be loaded when the API is unreachable', async () => {
    serve(() => new HttpResponse(null, { status: 503 }))
    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('could not be loaded')
  })

  it('opens the whole run in a drawer when a row is clicked', async () => {
    server.use(
      http.get('/api/v1/sync-runs/run-2', () =>
        HttpResponse.json({
          ...runs[0],
          error: 'page 2 failed: the source answered 502 and then some more that the list cut',
          watermark: null,
          sourceId: null,
          details: { accounts: 2 }
        })
      )
    )
    const wrapper = await mountView()

    await wrapper.find('[data-test="run-run-2"]').trigger('click')
    await flushPromises()

    const drawer = wrapper.find('[role="dialog"]')
    expect(drawer.exists()).toBe(true)
    expect(drawer.text()).toContain('run-2')
    expect(drawer.find('[data-test="run-error"]').text()).toContain('then some more')
    expect(JSON.parse(drawer.find('[data-test="run-details"]').text())).toEqual({ accounts: 2 })
  })

  it('reloads the first page when a re-run starts, so the new run is at the top', async () => {
    server.use(
      http.get('/api/v1/sync-runs/run-2', () =>
        HttpResponse.json({ ...runs[0], watermark: null, sourceId: null, details: {} })
      ),
      http.post('/api/v1/connectors/github/sync', () =>
        HttpResponse.json({ syncRunId: 'run-3' }, { status: 202 })
      )
    )
    const wrapper = await mountView()
    await wrapper.find('[data-test="run-run-2"]').trigger('click')
    await flushPromises()
    serve(() => HttpResponse.json(page([run('run-3', { status: 'RUNNING' }), ...runs])))

    await wrapper.find('[role="dialog"] [data-test="rerun"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')[0].attributes('data-test')).toBe('run-run-3')
  })

  it('closes the drawer', async () => {
    server.use(
      http.get('/api/v1/sync-runs/run-2', () =>
        HttpResponse.json({ ...runs[0], watermark: null, sourceId: null, details: {} })
      )
    )
    const wrapper = await mountView()
    await wrapper.find('[data-test="run-run-2"]').trigger('click')
    await flushPromises()

    await wrapper.find('[role="dialog"] [data-test="close-drawer"]').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('tells the drawer when the instance is read-only', async () => {
    server.use(
      http.get('/actuator/info', () => HttpResponse.json(info(true))),
      http.get('/api/v1/sync-runs/run-2', () =>
        HttpResponse.json({ ...runs[0], watermark: null, sourceId: null, details: {} })
      )
    )
    const wrapper = await mountView()
    await wrapper.find('[data-test="run-run-2"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="rerun"]').attributes('disabled')).toBeDefined()
  })
})
