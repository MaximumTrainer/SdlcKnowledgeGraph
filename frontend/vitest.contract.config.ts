import { fileURLToPath, URL } from 'node:url'
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

/**
 * Consumer contract tests, kept apart from the unit suite.
 *
 * Pact starts a real HTTP mock server per file and writes to a shared pact document, so these need
 * the node environment rather than jsdom, and they must not run in parallel with each other.
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'node',
      root: fileURLToPath(new URL('./', import.meta.url)),
      include: ['src/**/*.pact.spec.ts'],
      fileParallelism: false
    }
  })
)
