import { defineConfig, devices } from '@playwright/test'

/**
 * The site is static, so these run against a real build served by `vitepress preview` rather than a
 * dev server: what is asserted is what would be published. The build carries the `/SdlcKnowledgeGraph/`
 * base it is published under, so the tests address pages beneath it; a site that only worked at the
 * root would pass here and 404 on GitHub Pages.
 */
const base = process.env.VITEPRESS_BASE ?? '/SdlcKnowledgeGraph/'

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  reporter: [['list']],
  use: { baseURL: `http://localhost:4173${base}` },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run build && npm run preview',
    url: `http://localhost:4173${base}`,
    reuseExistingServer: false,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe'
  }
})
