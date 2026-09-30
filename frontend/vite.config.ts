import { fileURLToPath, URL } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * What nginx's /auth-config.json says in a container (#114), for the dev server: the login settings
 * from OIDC_AUTHORITY and OIDC_CLIENT_ID. Unset means no login, which suits an API started read-only
 * with no identity provider; `OIDC_AUTHORITY=http://localhost:8081/realms/sdlc npm run dev` signs in
 * with the compose Keycloak, for an API started against it (docs/AUTH.md, Running from source).
 */
const authConfig = (): Plugin => ({
  name: 'auth-config',
  configureServer(server) {
    server.middlewares.use('/auth-config.json', (_request, response) => {
      response.setHeader('Content-Type', 'application/json')
      response.setHeader('Cache-Control', 'no-store')
      response.end(
        JSON.stringify({
          authority: process.env.OIDC_AUTHORITY ?? '',
          clientId: process.env.OIDC_CLIENT_ID ?? 'sdlc-ui'
        })
      )
    })
  }
})

export default defineConfig({
  plugins: [vue(), authConfig()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
