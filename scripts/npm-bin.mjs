#!/usr/bin/env node
/**
 * Resolves the CLI entry point of an installed dev dependency.
 *
 *   import { resolveBin } from './npm-bin.mjs'
 *   spawnSync(process.execPath, [resolveBin('secretlint'), ...args])
 *
 * The hooks used to reach these tools through `npx --no-install`, which costs about ten seconds per
 * invocation on Windows against two for the work itself. Spawning the CLI with the running Node
 * binary skips the resolution npx repeats every time.
 *
 * The path comes from the package's own `bin` field rather than a hardcoded one under node_modules,
 * so a package that reorganises its files fails to resolve loudly instead of silently skipping the
 * check it was supposed to run.
 */
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'

const require = createRequire(import.meta.url)

export const resolveBin = (pkg, binName = pkg) => {
  let manifestPath
  try {
    manifestPath = require.resolve(`${pkg}/package.json`)
  } catch {
    console.error(`${pkg} is not installed. Run \`npm install\` at the repository root.`)
    process.exit(1)
  }

  const { bin } = JSON.parse(readFileSync(manifestPath, 'utf8'))
  const entry = typeof bin === 'string' ? bin : bin?.[binName]
  if (!entry) {
    console.error(`The ${pkg} package declares no ${binName} bin; its layout has changed.`)
    process.exit(1)
  }

  return path.join(path.dirname(manifestPath), entry)
}
