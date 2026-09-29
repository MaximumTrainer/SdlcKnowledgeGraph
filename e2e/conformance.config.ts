import { defineConfig } from '@playwright/test'

/**
 * The deployment contract in docs/DEPLOYMENT.md, as tests that run against any deployed instance.
 *
 *   CONFORMANCE_BASE_URL=https://sdlc-graph.fly.dev npx playwright test --config=conformance.config.ts
 *
 * Unlike the browser suite this starts nothing: it is pointed at a deployment that already exists,
 * from outside, the way a user reaches it. Every test title begins with the requirement it proves,
 * so a failure reads as the rule that was broken.
 *
 * No retries. A deployment that answers correctly only on the second attempt is not conforming, and
 * a retry would hide exactly that.
 */
const baseURL = process.env.CONFORMANCE_BASE_URL
if (!baseURL) {
  throw new Error('Set CONFORMANCE_BASE_URL to the deployment to check, e.g. http://localhost:5173')
}

export default defineConfig({
  testDir: './conformance',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list']],
  // Requests only, no browser: nothing here needs one, and the deploy job then needs no browser install.
  use: { baseURL },
  timeout: 120_000
})
