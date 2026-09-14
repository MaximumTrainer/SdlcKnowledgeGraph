import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeEditor from './NodeEditor.vue'

/**
 * The editor renders itself from the ontology, so these assert the mapping from declared property
 * type to input, and the rule the old hand-written editor broke: an edit must update the node it is
 * editing rather than creating a second one.
 */
const team = {
  id: 'Team:platform',
  type: 'Team',
  key: 'platform',
  props: { name: 'platform', email: 'platform@acme.example' },
  provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
}

const router = (): Router =>
  createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/nodes/:type', name: 'nodes', component: { template: '<div />' } },
      { path: '/nodes/:type/:id', name: 'node', component: { template: '<div />' } }
    ]
  })

const editorFor = async (props: { type: string; id?: string }) => {
  const wrapper = mount(NodeEditor, { props, global: { plugins: [router()] } })
  await flushPromises()
  return wrapper
}

describe('NodeEditor', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Team/platform', () => HttpResponse.json(team))
    )
  })

  it('builds one input per declared property, typed as the ontology says', async () => {
    const wrapper = await editorFor({ type: 'Sample' })

    expect(wrapper.find('input[name="name"]').attributes('type')).toBe('text')
    expect(wrapper.find('input[name="count"]').attributes('type')).toBe('number')
    expect(wrapper.find('input[name="enabled"]').attributes('type')).toBe('checkbox')
    expect(wrapper.find('input[name="seenAt"]').attributes('type')).toBe('datetime-local')
    expect(wrapper.find('[data-test="tag-input-topics"]').exists()).toBe(true)
  })

  it('marks the properties the ontology requires', async () => {
    const wrapper = await editorFor({ type: 'Sample' })

    expect(wrapper.find('label[for="field-name"]').text()).toContain('*')
    expect(wrapper.find('label[for="field-count"]').text()).not.toContain('*')
  })

  it('refuses to submit a missing required property, and sends nothing', async () => {
    let posted = false
    server.use(
      http.post('/api/v1/nodes/Sample', () => {
        posted = true
        return HttpResponse.json({}, { status: 201 })
      })
    )
    const wrapper = await editorFor({ type: 'Sample' })

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('name is required')
    expect(posted).toBe(false)
  })

  it('creates when there is no id', async () => {
    const called: string[] = []
    server.use(
      http.post('/api/v1/nodes/Team', () => {
        called.push('POST')
        return HttpResponse.json(team, { status: 201 })
      })
    )
    const wrapper = await editorFor({ type: 'Team' })

    await wrapper.find('input[name="name"]').setValue('platform')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(called).toEqual(['POST'])
  })

  it('updates, and never creates, when editing an existing node', async () => {
    const called: string[] = []
    server.use(
      http.post('/api/v1/nodes/Team', () => {
        called.push('POST')
        return HttpResponse.json(team, { status: 201 })
      }),
      http.put('/api/v1/nodes/Team/platform', () => {
        called.push('PUT')
        return HttpResponse.json(team)
      })
    )
    const wrapper = await editorFor({ type: 'Team', id: 'platform' })

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(called).toEqual(['PUT'])
  })

  it('loads the existing values into the form when editing', async () => {
    const wrapper = await editorFor({ type: 'Team', id: 'platform' })

    expect((wrapper.find('input[name="name"]').element as HTMLInputElement).value).toBe('platform')
    expect((wrapper.find('input[name="email"]').element as HTMLInputElement).value).toBe(
      'platform@acme.example'
    )
  })

  it('disables identity properties when editing, because the server will refuse to move them', async () => {
    const wrapper = await editorFor({ type: 'Team', id: 'platform' })

    expect(wrapper.find('input[name="name"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('input[name="email"]').attributes('disabled')).toBeUndefined()
  })

  it('leaves identity properties editable when creating', async () => {
    const wrapper = await editorFor({ type: 'Team' })

    expect(wrapper.find('input[name="name"]').attributes('disabled')).toBeUndefined()
  })

  it('shows what the server refused rather than failing silently', async () => {
    server.use(
      http.post('/api/v1/nodes/Team', () =>
        HttpResponse.json({ error: 'node exists', existingId: 'Team:platform' }, { status: 409 })
      )
    )
    const wrapper = await editorFor({ type: 'Team' })

    await wrapper.find('input[name="name"]').setValue('platform')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('already exists')
  })

  it('shows a server-side field error against its own field', async () => {
    server.use(
      http.post('/api/v1/nodes/Team', () =>
        HttpResponse.json(
          { errors: [{ field: 'name', message: 'name is required' }] },
          { status: 400 }
        )
      )
    )
    const wrapper = await editorFor({ type: 'Team' })

    await wrapper.find('input[name="name"]').setValue('platform')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-test="error-name"]').text()).toContain('name is required')
  })
})

/**
 * The remote is the one thing a person types, and the key it resolves to is what the graph will
 * store it under. Showing that key as they type is the difference between finding out now and
 * finding out after saving - and it is the same parser the API uses, so what is previewed is what
 * will happen (#8).
 */
describe('NodeEditor, registering a repository', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)))
  })

  const mountEditor = async () => {
    const r = router()
    await r.push('/nodes/Repository/new')
    await r.isReady()
    const wrapper = mount(NodeEditor, { global: { plugins: [r] }, props: { type: 'Repository' } })
    await flushPromises()
    return wrapper
  }

  it('previews the key a remote will be stored under, as it is typed', async () => {
    const wrapper = await mountEditor()

    await wrapper.find('#field-url').setValue('git@github.com:Acme/Payments.git')
    await flushPromises()

    expect(wrapper.find('[data-test="remote-key"]').text()).toContain('github.com/acme/payments')
  })

  it('says why a remote is not one, rather than waiting for the server to', async () => {
    const wrapper = await mountEditor()

    await wrapper.find('#field-url').setValue('https://example.com/page')
    await flushPromises()

    expect(wrapper.find('[data-test="remote-key"]').text()).toMatch(/organisation and a repository/)
  })

  it('shows nothing while the field is empty', async () => {
    const wrapper = await mountEditor()

    expect(wrapper.find('[data-test="remote-key"]').exists()).toBe(false)
  })
})
