import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nodeRoute } from './nodeRoute'

/**
 * Where a node's page is (#85). A key with slashes is written into the route as it is, one segment
 * per part, as it always has been. An ExternalWorkItem is keyed by its URI, and the `//` in a URI
 * would leave an empty segment no route matches, so a key holding one is encoded as one segment.
 */
describe('nodeRoute', () => {
  it('writes a key with slashes into the route as it is', () => {
    expect(nodeRoute('Repository', 'github.com/acme/payments')).toBe(
      '/nodes/Repository/github.com/acme/payments'
    )
    expect(nodeRoute('Change', 'github.com/acme/payments@a1b2c3')).toBe(
      '/nodes/Change/github.com/acme/payments@a1b2c3'
    )
  })

  it('encodes a key holding a double slash as one segment', () => {
    expect(nodeRoute('ExternalWorkItem', 'chorus://task/01JABC')).toBe(
      '/nodes/ExternalWorkItem/chorus%3A%2F%2Ftask%2F01JABC'
    )
  })

  it('appends a sub-page after the key', () => {
    expect(nodeRoute('Team', 'platform', 'edit')).toBe('/nodes/Team/platform/edit')
    expect(nodeRoute('ExternalWorkItem', 'https://acme.atlassian.net/browse/PAY-42', 'edit')).toBe(
      '/nodes/ExternalWorkItem/https%3A%2F%2Facme.atlassian.net%2Fbrowse%2FPAY-42/edit'
    )
  })

  it('round-trips through the router the application uses, back to the key', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/nodes/:type/:id+', component: { template: '<div />' } }]
    })

    await router.push(nodeRoute('ExternalWorkItem', 'chorus://task/01JABC'))

    expect(router.currentRoute.value.params.id).toEqual(['chorus://task/01JABC'])
  })
})
