import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import SyncRunDrawer from './SyncRunDrawer.vue'

/**
 * One run in full, and the way to run it again (#29, FR8).
 *
 * Re-running is a write, and a public instance refuses writes (docs/USER-GUIDE.md, read-only
 * instances). So the drawer does not offer what the instance will refuse, and when a refusal comes
 * anyway it shows the API's own reason rather than a generic failure.
 */
const detail = (overrides = {}) => ({
  id: 'run-2',
  connector: 'github',
  sourceSystem: 'github',
  mode: 'FULL',
  status: 'FAILED',
  startedAt: '2026-09-01T10:00:00Z',
  finishedAt: '2026-09-01T10:01:30Z',
  durationMs: 90_000,
  nodesUpserted: 3,
  edgesUpserted: 2,
  tombstones: 1,
  watermark: '2026-09-01T09:00:00Z',
  sourceId: null,
  error: 'connectors.github needs at least one org and a token before it can read anything',
  details: { accounts: { acme: 3 } },
  ...overrides
})

/** The mode each re-run asked for, so a test can say which one was sent. */
let requestedModes: (string | null)[]

const serve = (run = detail()) =>
  server.use(http.get(`/api/v1/sync-runs/${run.id}`, () => HttpResponse.json(run)))

const answerSync = (reply: () => Response) =>
  server.use(
    http.post('/api/v1/connectors/github/sync', ({ request }) => {
      requestedModes.push(new URL(request.url).searchParams.get('mode'))
      return reply()
    })
  )

const mountDrawer = async (props: { runId?: string; readOnly?: boolean } = {}) => {
  const wrapper = mount(SyncRunDrawer, {
    props: { runId: 'run-2', readOnly: false, ...props },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

describe('SyncRunDrawer', () => {
  beforeEach(() => {
    requestedModes = []
    document.body.innerHTML = ''
    serve()
  })

  it('is a labelled dialog that takes focus, so keyboard and screen reader users land in it', async () => {
    const wrapper = await mountDrawer()

    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.attributes('aria-modal')).toBe('true')
    const heading = document.getElementById(dialog.attributes('aria-labelledby')!)
    expect(heading?.textContent).toContain('Sync run')
    expect(document.activeElement).toBe(wrapper.find('[data-test="close-drawer"]').element)
  })

  it('shows the whole run: counts, times, watermark, error and details as JSON', async () => {
    const wrapper = await mountDrawer()

    expect(wrapper.text()).toContain('run-2')
    expect(wrapper.find('[data-test="status-chip"]').text()).toBe('FAILED')
    expect(wrapper.text()).toContain('1 m 30 s')
    expect(wrapper.text()).toContain('2026-09-01 09:00:00 UTC')
    expect(wrapper.find('[data-test="run-error"]').text()).toContain('needs at least one org')
    expect(JSON.parse(wrapper.find('[data-test="run-details"]').text())).toEqual({
      accounts: { acme: 3 }
    })
  })

  it('leaves the error out for a run that had none', async () => {
    serve(detail({ status: 'SUCCESS', error: null }))
    const wrapper = await mountDrawer()

    expect(wrapper.find('[data-test="run-error"]').exists()).toBe(false)
  })

  it('says the run no longer exists when it has been pruned', async () => {
    server.use(
      http.get('/api/v1/sync-runs/run-2', () =>
        HttpResponse.json({ error: 'sync run not found', id: 'run-2' }, { status: 404 })
      )
    )
    const wrapper = await mountDrawer()

    expect(wrapper.find('[role="alert"]').text()).toContain('no longer recorded')
  })

  it('closes on the close button and on Escape', async () => {
    const wrapper = await mountDrawer()

    await wrapper.find('[data-test="close-drawer"]').trigger('click')
    await wrapper.find('[role="dialog"]').trigger('keydown', { key: 'Escape' })

    expect(wrapper.emitted('close')).toHaveLength(2)
  })

  it('re-runs a full run in full, and reports the run it started', async () => {
    answerSync(() => HttpResponse.json({ syncRunId: 'run-3' }, { status: 202 }))
    const wrapper = await mountDrawer()

    await wrapper.find('[data-test="rerun"]').trigger('click')
    await flushPromises()

    expect(requestedModes).toEqual(['full'])
    const result = wrapper.find('[data-test="rerun-result"]')
    expect(result.text()).toContain('Started run run-3')
    expect(result.attributes('data-run-id')).toBe('run-3')
    expect(wrapper.emitted('started')).toEqual([['run-3']])
  })

  it('re-runs anything else incrementally, since only full and incremental can be asked for', async () => {
    serve(detail({ mode: 'WEBHOOK' }))
    answerSync(() => HttpResponse.json({ syncRunId: 'run-3' }, { status: 202 }))
    const wrapper = await mountDrawer()

    await wrapper.find('[data-test="rerun"]').trigger('click')
    await flushPromises()

    expect(requestedModes).toEqual(['incremental'])
  })

  it('does not offer a re-run on a read-only instance, and says why', async () => {
    const wrapper = await mountDrawer({ readOnly: true })

    const button = wrapper.find('[data-test="rerun"]')
    expect(button.attributes('disabled')).toBeDefined()
    const note = document.getElementById(button.attributes('aria-describedby')!)
    expect(note?.textContent).toContain('read-only')
  })

  it('shows the API’s own refusal when a write is refused anyway', async () => {
    answerSync(() => HttpResponse.json({ error: 'this instance is read-only' }, { status: 403 }))
    const wrapper = await mountDrawer()

    await wrapper.find('[data-test="rerun"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('this instance is read-only')
    expect(wrapper.emitted('started')).toBeUndefined()
  })

  it('says a run is already going when the connector is busy', async () => {
    answerSync(() =>
      HttpResponse.json({ error: 'sync in progress', connector: 'github' }, { status: 409 })
    )
    const wrapper = await mountDrawer()

    await wrapper.find('[data-test="rerun"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('github is already syncing')
  })
})
