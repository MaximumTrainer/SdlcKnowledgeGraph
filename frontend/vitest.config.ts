import { fileURLToPath, URL } from 'node:url'
import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      root: fileURLToPath(new URL('./', import.meta.url)),
      setupFiles: ['src/test/setup.ts'],
      include: ['src/**/*.spec.ts'],
      // Contract tests run under vitest.contract.config.ts; see docs/TESTING.md.
      exclude: ['src/**/*.pact.spec.ts'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'lcov'],
        include: ['src/**/*.{ts,vue}'],
        exclude: ['src/test/**', 'src/**/*.spec.ts', 'src/main.ts']
      }
    }
  })
)
