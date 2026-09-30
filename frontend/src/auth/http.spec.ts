import { describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { http, HttpResponse } from 'msw'
import { ref } from 'vue'
import { server } from '@/test/msw/server'
import { attachAuth } from './http'
import type { AuthSession } from './session'

/** Every call to the API carries the token, and a 401 restarts the login rather than erroring (#114). */
const session = (token: string | null): AuthSession => ({
  username: ref(null),
  scopes: ref([]),
  accessToken: vi.fn(async () => token),
  signIn: vi.fn(async () => undefined),
  completeSignIn: vi.fn(async () => '/'),
  signOut: vi.fn(async () => undefined)
})

describe('attachAuth', () => {
  it('sends the access token as a bearer token', async () => {
    let authorization: string | null = null
    server.use(
      http.get('/api/v1/nodes/Team', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json({ items: [] })
      })
    )
    const client = axios.create({ baseURL: '/api/v1' })
    attachAuth(client, session('access-token'))

    await client.get('/nodes/Team')

    expect(authorization).toBe('Bearer access-token')
  })

  it('sends no Authorization header when there is no token', async () => {
    let authorization: string | null = 'unset'
    server.use(
      http.get('/api/v1/nodes/Team', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return HttpResponse.json({ items: [] })
      })
    )
    const client = axios.create({ baseURL: '/api/v1' })
    attachAuth(client, session(null))

    await client.get('/nodes/Team')

    expect(authorization).toBeNull()
  })

  it('restarts the login on a 401, returning to where the user was', async () => {
    server.use(
      http.get('/api/v1/nodes/Team', () =>
        HttpResponse.json({ error: 'authentication required' }, { status: 401 })
      )
    )
    const client = axios.create({ baseURL: '/api/v1' })
    const s = session('stale-token')
    attachAuth(client, s, () => '/nodes/Team')

    await expect(client.get('/nodes/Team')).rejects.toThrow()

    expect(s.signIn).toHaveBeenCalledWith('/nodes/Team')
  })

  it('leaves any other failure to the caller', async () => {
    server.use(http.get('/api/v1/nodes/Team', () => HttpResponse.json({}, { status: 500 })))
    const client = axios.create({ baseURL: '/api/v1' })
    const s = session('access-token')
    attachAuth(client, s)

    await expect(client.get('/nodes/Team')).rejects.toThrow()

    expect(s.signIn).not.toHaveBeenCalled()
  })
})
