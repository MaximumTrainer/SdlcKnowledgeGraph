import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * The login journey (#114), run against the compose stack with authentication on: Keycloak from the
 * `auth` profile, and the API and web interface pointed at it by compose.auth.yaml.
 *
 *   docker compose -f compose.yaml -f compose.auth.yaml --profile auth up -d --build --wait
 *   npx playwright test --config=auth.config.ts
 *
 * Separate from the main browser suite, which runs against the default stack with the development
 * bypass (AUTH_DISABLED=true) and so never sees a login page.
 */
export default defineConfig({
  testDir: './auth',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  failOnFlakyTests: !!process.env.CI,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report/auth' }]],
  use: {
    baseURL: 'http://localhost:5173',
    testIdAttribute: 'data-test',
    trace: 'on-first-retry'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command:
      'docker compose -f compose.yaml -f compose.auth.yaml --profile auth up -d --build --wait',
    cwd: repoRoot,
    url: 'http://localhost:5173/healthz',
    reuseExistingServer: true,
    timeout: 10 * 60 * 1000,
    stdout: 'pipe',
    stderr: 'pipe'
  }
})
