import { describe, expect, it, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { server } from '@/test/msw/server'
import { ontologyFixture } from '@/test/fixtures/ontology'
import NodeEditor from './NodeEditor.vue'
import { insufficientScope } from '@/test/authSession'

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

  it('says which scope was missing when a save is refused for it (#116)', async () => {
    server.use(
      http.post('/api/v1/nodes/Team', () => HttpResponse.json(insufficientScope, { status: 403 }))
    )
    const wrapper = await editorFor({ type: 'Team' })

    await wrapper.find('input[name="name"]').setValue('platform')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-test="form-error"]').text()).toBe(
      'You do not have permission to do that: it needs graph:write, and you hold graph:read.'
    )
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
 * An instant is entered in a `datetime-local` input, which has no zone and no seconds; the API takes
 * an ISO-8601 instant. The input is read as UTC, as the run history's filters are, so a Change's
 * `committedAt` means the same moment whoever typed it (#85).
 */
describe('NodeEditor, an instant', () => {
  const sample = {
    id: 'Sample:s1',
    type: 'Sample',
    key: 's1',
    props: { name: 's1', seenAt: '2026-09-13T10:00:00Z' },
    provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
  }

  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Sample/s1', () => HttpResponse.json(sample))
    )
  })

  it('is sent as the UTC instant its input names', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/nodes/Sample', async ({ request }) => {
        sent = ((await request.json()) as { props: Record<string, unknown> }).props
        return HttpResponse.json(sample, { status: 201 })
      })
    )
    const wrapper = await editorFor({ type: 'Sample' })

    await wrapper.find('input[name="name"]').setValue('s1')
    await wrapper.find('input[name="seenAt"]').setValue('2026-09-13T10:00')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(sent.seenAt).toBe('2026-09-13T10:00:00Z')
  })

  it('is shown in its input when editing, to the minute, in UTC', async () => {
    const wrapper = await editorFor({ type: 'Sample', id: 's1' })

    expect((wrapper.find('input[name="seenAt"]').element as HTMLInputElement).value).toBe(
      '2026-09-13T10:00'
    )
  })

  it('left empty is left out, not sent as an empty string', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/nodes/Sample', async ({ request }) => {
        sent = ((await request.json()) as { props: Record<string, unknown> }).props
        return HttpResponse.json(sample, { status: 201 })
      })
    )
    const wrapper = await editorFor({ type: 'Sample' })

    await wrapper.find('input[name="name"]').setValue('s1')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect('seenAt' in sent).toBe(false)
  })
})

/** A node keyed by a URI opens at its key encoded as one segment, which a route can match (#85). */
describe('NodeEditor, a key holding a double slash', () => {
  it('opens the saved node at its key encoded as one segment', async () => {
    const saved = {
      id: 'Sample:chorus://task/01JABC',
      type: 'Sample',
      key: 'chorus://task/01JABC',
      props: { name: 'chorus://task/01JABC' },
      provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
    }
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.post('/api/v1/nodes/Sample', () => HttpResponse.json(saved, { status: 201 }))
    )
    const r = router()
    const wrapper = mount(NodeEditor, { props: { type: 'Sample' }, global: { plugins: [r] } })
    await flushPromises()

    await wrapper.find('input[name="name"]').setValue('chorus://task/01JABC')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(r.currentRoute.value.fullPath).toBe('/nodes/Sample/chorus%3A%2F%2Ftask%2F01JABC')
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

/**
 * The registry says what each property means, shows a value, and names the only values a closed set
 * allows (#81). The form uses all three: a select for a closed set, the description beside the field,
 * and the first example as the placeholder, so a person sees what an agent reading the ontology sees.
 */
describe('NodeEditor, a self-described property', () => {
  const stored = {
    id: 'Sample:s1',
    type: 'Sample',
    key: 's1',
    props: { name: 's1', stage: 'retired' },
    provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
  }

  beforeEach(() => {
    server.use(
      http.get('/api/v1/ontology', () => HttpResponse.json(ontologyFixture)),
      http.get('/api/v1/nodes/Sample/s1', () => HttpResponse.json(stored))
    )
  })

  const options = (wrapper: Awaited<ReturnType<typeof editorFor>>, name: string) =>
    wrapper.findAll(`select[name="${name}"] option`).map(option => option.attributes('value'))

  it('offers a closed set as a choice of its values, and none of an optional one', async () => {
    const wrapper = await editorFor({ type: 'Sample' })

    expect(wrapper.find('select[name="stage"]').exists()).toBe(true)
    expect(wrapper.find('input[name="stage"]').exists()).toBe(false)
    expect(options(wrapper, 'stage')).toEqual(['', 'draft', 'live'])
  })

  it('asks for a choice of a required closed set, without offering none as one', async () => {
    const wrapper = await editorFor({ type: 'Gadget' })

    const placeholder = wrapper.find('select[name="kind"] option[value=""]')
    expect(placeholder.attributes('disabled')).toBeDefined()
    expect(options(wrapper, 'kind')).toEqual(['', 'library', 'api'])
  })

  it('shows the description as help, tied to its field', async () => {
    const wrapper = await editorFor({ type: 'Sample' })

    const help = wrapper.find('#help-name')
    expect(help.text()).toBe("The sample's name, unique among samples")
    expect(wrapper.find('input[name="name"]').attributes('aria-describedby')).toContain('help-name')
  })

  it('shows the first example as the placeholder', async () => {
    const wrapper = await editorFor({ type: 'Sample' })

    expect(wrapper.find('input[name="name"]').attributes('placeholder')).toBe('alpha')
    expect(wrapper.find('input[name="count"]').attributes('placeholder')).toBeUndefined()
  })

  it('sends the value chosen', async () => {
    let sent: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/nodes/Sample', async ({ request }) => {
        sent = ((await request.json()) as { props: Record<string, unknown> }).props
        return HttpResponse.json({ ...stored, props: sent }, { status: 201 })
      })
    )
    const wrapper = await editorFor({ type: 'Sample' })

    await wrapper.find('input[name="name"]').setValue('s2')
    await wrapper.find('select[name="stage"]').setValue('live')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(sent).toMatchObject({ name: 's2', stage: 'live' })
  })

  it('keeps a stored value outside the set visible, rather than silently changing it', async () => {
    // Written before the set was declared: the server will refuse it on save and say which values
    // it allows, but the form shows what is stored rather than pretending it is something else.
    const wrapper = await editorFor({ type: 'Sample', id: 's1' })

    expect(options(wrapper, 'stage')).toEqual(['', 'draft', 'live', 'retired'])
    expect((wrapper.find('select[name="stage"]').element as HTMLSelectElement).value).toBe(
      'retired'
    )
  })
})
