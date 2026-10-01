import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import PolicyView from './PolicyView.vue'

/**
 * The authorisation policy page (#30, #95): which revision is in force, and what the policy decides
 * for the signed-in user, with the rule that decided and why.
 */
const STATUS = {
  name: 'sdlc-authz',
  revision: 'sdlc-authz-1.0.0',
  loadedAt: '2026-10-01T12:00:00Z',
  engine: 'embedded-wasm',
  status: 'UP',
  failMode: 'closed'
}

const ONTOLOGY = {
  version: '1.10.0',
  nodeTypes: [
    { name: 'Repository', meta: false, properties: [], identity: [], sensitivity: 'internal' },
    {
      name: 'ServicePrincipal',
      meta: true,
      properties: [],
      identity: [],
      sensitivity: 'restricted'
    }
  ],
  edgeTypes: []
}

const explanation = (overrides: Record<string, unknown> = {}) => ({
  allow: false,
  policy: 'roles',
  reason: 'the role viewer may not update Repository',
  required: ['graph:write'],
  redact: [],
  clearance: 'internal',
  subject: { id: 'viewer', kind: 'user', scopes: ['graph:read'], roles: ['viewer'], teams: [] },
  ...overrides
})

const mountView = async () => {
  const wrapper = mount(PolicyView)
  await flushPromises()
  return wrapper
}

describe('PolicyView', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/policy', () => HttpResponse.json(STATUS)),
      http.get('/api/v1/ontology', () => HttpResponse.json(ONTOLOGY))
    )
  })

  it('names the revision in force and that it fails closed', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('h1').text()).toBe('Authorisation policy')
    expect(wrapper.find('[data-test="policy-revision"]').text()).toBe('sdlc-authz-1.0.0')
    expect(wrapper.find('[data-test="policy-status"]').text()).toContain('requests are refused')
  })

  it('explains a refusal, naming the rule and why', async () => {
    let asked: unknown = null
    server.use(
      http.post('/api/v1/policy/explain', async ({ request }) => {
        asked = await request.json()
        return HttpResponse.json(explanation())
      })
    )
    const wrapper = await mountView()

    await wrapper.find('[data-test="explain-action"]').setValue('update')
    await wrapper.find('[data-test="explain-type"]').setValue('Repository')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(asked).toEqual({ action: 'update', resource: { type: 'Repository' } })
    expect(wrapper.find('[data-test="explain-verdict"]').text()).toContain('Denied')
    expect(wrapper.find('[data-test="explain-verdict"]').text()).toContain('roles')
    expect(wrapper.find('[data-test="explain-reason"]').text()).toBe(
      'the role viewer may not update Repository'
    )
    expect(wrapper.find('[data-test="explain-clearance"]').text()).toBe('internal')
  })

  it('says a token without roles is judged by its scopes alone', async () => {
    server.use(
      http.post('/api/v1/policy/explain', () =>
        HttpResponse.json(
          explanation({
            allow: true,
            policy: 'allow',
            reason: 'the token holds graph:write',
            clearance: 'restricted',
            subject: { id: 'dan', kind: 'user', scopes: ['graph:write'], roles: null, teams: [] }
          })
        )
      )
    )
    const wrapper = await mountView()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-test="explain-verdict"]').text()).toContain('Allowed')
    expect(wrapper.find('[data-test="explain-result"]').text()).toContain('judged by the token')
  })

  it('says so when the policy in force cannot be read', async () => {
    server.use(http.get('/api/v1/policy', () => new HttpResponse(null, { status: 503 })))
    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('could not be loaded')
  })
})
