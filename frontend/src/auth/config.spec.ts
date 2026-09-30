import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/msw/server'
import { loadAuthConfig } from './config'

/**
 * Whether the web interface signs users in is decided where it is deployed, not when it is built:
 * nginx serves /auth-config.json from its environment (#114). One image then serves the default
 * stack, which has no identity provider, and the `auth` profile, which has Keycloak.
 */
describe('loadAuthConfig', () => {
  it('returns the identity provider the deployment names', async () => {
    server.use(
      http.get('/auth-config.json', () =>
        HttpResponse.json({ authority: 'http://localhost:8081/realms/sdlc', clientId: 'sdlc-ui' })
      )
    )

    expect(await loadAuthConfig()).toEqual({
      authority: 'http://localhost:8081/realms/sdlc',
      clientId: 'sdlc-ui'
    })
  })

  it('is off when the deployment names no identity provider', async () => {
    server.use(
      http.get('/auth-config.json', () => HttpResponse.json({ authority: '', clientId: 'sdlc-ui' }))
    )

    expect(await loadAuthConfig()).toBeNull()
  })

  it('is off when nothing serves the configuration, as under the Vite dev server', async () => {
    server.use(http.get('/auth-config.json', () => new HttpResponse(null, { status: 404 })))

    expect(await loadAuthConfig()).toBeNull()
  })
})
