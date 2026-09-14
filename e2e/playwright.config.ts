import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

/**
 * End-to-end tests run against the real stack from compose.yaml (nginx -> backend -> Neo4j).
 * Playwright brings the stack up itself; set reuseExistingServer so an already-running stack is used as-is.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    // The application marks test hooks with `data-test`, so getByTestId has to look for that rather
    // than Playwright's default `data-testid`. Two of the ten used the default spelling, which is
    // how the inconsistency stayed invisible until a new spec reached for one of the other eight.
    testIdAttribute: 'data-test',
    trace: 'on-first-retry'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'docker compose up -d --build --wait',
    cwd: repoRoot,
    url: 'http://localhost:5173/healthz',
    reuseExistingServer: true,
    timeout: 10 * 60 * 1000,
    stdout: 'pipe',
    stderr: 'pipe'
  }
})
