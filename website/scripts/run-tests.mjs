#!/usr/bin/env node
// Runs every `*.test.mjs` under scripts/ with the node test runner.
//
// `node --test <path>` is not portable across the versions this repository sees: Node 20 searches a
// directory argument, Node 22 and later treat the argument as a file and expand globs themselves. A
// script written for either one fails on the other — silently skipping tests in the worst case. So
// the files are discovered here and passed explicitly, which every version agrees on.
import { spawnSync } from 'node:child_process'
import { readdirSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

const here = fileURLToPath(new URL('.', import.meta.url))
const tests = readdirSync(here)
  .filter(name => name.endsWith('.test.mjs'))
  .sort()
  .map(name => `scripts/${name}`)

if (tests.length === 0) {
  console.error('No test files found under scripts/. That is a bug in the test setup, not a pass.')
  process.exit(1)
}

const { status } = spawnSync(process.execPath, ['--test', ...tests], { stdio: 'inherit' })
process.exit(status ?? 1)
