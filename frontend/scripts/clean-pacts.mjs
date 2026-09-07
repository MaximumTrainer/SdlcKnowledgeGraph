// Pact merges into an existing document, so a renamed or removed interaction would linger and the
// committed contract would stop being a faithful render of the specs. Regenerating from empty keeps
// `git diff --exit-code contracts/pacts` an honest check.
import { rmSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

const pactDirectory = fileURLToPath(new URL('../../contracts/pacts', import.meta.url))
rmSync(pactDirectory, { recursive: true, force: true })
