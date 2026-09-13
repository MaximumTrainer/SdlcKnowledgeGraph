import { defineConfig } from '@playwright/test'
import base from './playwright.config'
export default defineConfig({
  ...base,
  projects: [{ name: 'chromium', use: { ...(base.projects?.[0]?.use ?? {}), launchOptions: { executablePath: '/opt/pw-browsers/chromium' } } }]
})
