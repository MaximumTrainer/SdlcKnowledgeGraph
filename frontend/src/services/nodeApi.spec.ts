import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import { nodeApi } from './api'

const platform = {
  id: 'Team:platform',
  type: 'Team',
  key: 'platform',
  props: { name: 'platform' },
  provenance: { sourceSystem: 'manual', confidence: 1, inferred: false }
}

describe('nodeApi', () => {
  it('lists nodes of a type as a page', async () => {
    server.use(
      http.get('/api/v1/nodes/Team', () =>
        HttpResponse.json({ items: [platform], nextCursor: 'platform' })
      )
    )

    const page = await nodeApi.list('Team')

    expect(page.items[0].key).toBe('platform')
    expect(page.nextCursor).toBe('platform')
  })

  it('passes limit and cursor through as query parameters', async () => {
    let seen: URLSearchParams | undefined
    server.use(
      http.get('/api/v1/nodes/Team', ({ request }) => {
        seen = new URL(request.url).searchParams
        return HttpResponse.json({ items: [] })
      })
    )

    await nodeApi.list('Team', { limit: 10, cursor: 'core' })

    expect(seen?.get('limit')).toBe('10')
    expect(seen?.get('cursor')).toBe('core')
  })

  it('sends only props on create, never a client-invented identity', async () => {
    let body: unknown
    server.use(
      http.post('/api/v1/nodes/Team', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(platform, { status: 201 })
      })
    )

    await nodeApi.create('Team', { name: 'platform' })

    expect(body).toEqual({ props: { name: 'platform' } })
  })

  it('updates through PUT on the node itself', async () => {
    let method: string | undefined
    server.use(
      http.put('/api/v1/nodes/Team/platform', ({ request }) => {
        method = request.method
        return HttpResponse.json(platform)
      })
    )

    const updated = await nodeApi.update('Team', 'platform', { name: 'platform' })

    expect(method).toBe('PUT')
    expect(updated.key).toBe('platform')
  })

  it('leaves a key containing slashes unescaped, because the server reads it as a path', async () => {
    server.use(
      http.get('/api/v1/nodes/Repository/github.com/acme/payments', () =>
        HttpResponse.json({ ...platform, type: 'Repository', key: 'github.com/acme/payments' })
      )
    )

    const node = await nodeApi.get('Repository', 'github.com/acme/payments')

    expect(node.key).toBe('github.com/acme/payments')
  })

  it('asks for a cascading delete only when told to', async () => {
    const seen: (string | null)[] = []
    server.use(
      http.delete('/api/v1/nodes/Team/platform', ({ request }) => {
        seen.push(new URL(request.url).searchParams.get('cascade'))
        return new HttpResponse(null, { status: 204 })
      })
    )

    await nodeApi.remove('Team', 'platform')
    await nodeApi.remove('Team', 'platform', { cascade: true })

    expect(seen).toEqual([null, 'true'])
  })
})
