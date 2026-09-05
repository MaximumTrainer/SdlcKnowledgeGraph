// The end-to-end project is a separate npm package but should not have separate lint rules, so it
// reuses the frontend's flat config. Plugin imports inside that file resolve from
// frontend/node_modules, which is why this works without duplicating the plugin dependencies here.
import frontendConfig from '../frontend/eslint.config.js'

export default [
  { ignores: ['test-results/**', 'playwright-report/**', 'blob-report/**', 'node_modules/**'] },
  ...frontendConfig
]
