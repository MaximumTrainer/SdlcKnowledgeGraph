#!/usr/bin/env node
/**
 * Runs secretlint over the given paths.
 *
 *   node scripts/secretlint.mjs <paths...>
 *
 * A wrapper rather than a bare CLI call so that `--secretlintignore` lives in one place instead of in
 * both the hook and the CI entry point, where the two could drift apart and scan different trees.
 * See scripts/npm-bin.mjs for why the CLI is not reached through npx.
 */
import { spawnSync } from 'node:child_process'
import { resolveBin } from './npm-bin.mjs'

const paths = process.argv.slice(2)
if (paths.length === 0) process.exit(0)

const result = spawnSync(
  process.execPath,
  [resolveBin('secretlint'), '--secretlintignore', '.secretlintignore', ...paths],
  { stdio: 'inherit' },
)

if (result.error) {
  console.error(`Failed to run secretlint: ${result.error.message}`)
  process.exit(1)
}
process.exit(result.status ?? 1)
