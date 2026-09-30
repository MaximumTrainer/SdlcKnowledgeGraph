import { test as setup } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { SAVED_SESSION, signIn, storedSession } from '../support/session'

/**
 * Signs dan in once, through Keycloak's real login page, before the browser suite runs (#118), and
 * keeps the session the web interface stored so every test can start signed in (fixtures.ts).
 */
setup('dan signs in through Keycloak', async ({ page }) => {
  await signIn(page, 'dan')

  await mkdir(path.dirname(SAVED_SESSION), { recursive: true })
  await writeFile(SAVED_SESSION, JSON.stringify(await storedSession(page)))
})
