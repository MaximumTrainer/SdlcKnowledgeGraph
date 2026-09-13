#!/usr/bin/env node
/**
 * Runs the commit-time guards over the whole repository.
 *
 *   npm run guards                       # all of them
 *   node scripts/guards.mjs config       # only the named ones: hygiene | secrets | config
 *
 * The hooks check only what a commit touches, which is what makes them fast. This runs the same
 * checks over every tracked file, for two callers:
 *
 *   - CI, because `LEFTHOOK=0` bypasses the hooks, and a bypass that nothing re-checks is not a gate
 *     (ADR-0001). It also catches anything that entered the history before the guard existed.
 *   - anyone who wants to know whether the guards pass without making a commit.
 *
 * One Node entry point rather than a few shell lines, so the CI job on Linux and a developer on
 * Windows run the same thing - the same reason Gradle goes through scripts/gradle.mjs.
 */
import { spawnSync } from 'node:child_process'
import { resolveBin } from './npm-bin.mjs'

const tracked = spawnSync('git', ['ls-files'], { encoding: 'utf8' })
if (tracked.status !== 0) {
  console.error('Could not list tracked files; is this a git repository?')
  process.exit(1)
}
const files = tracked.stdout.split(/\r?\n/).filter(Boolean)

const node = process.execPath

const CHECKS = {
  // The path list goes over stdin: a few hundred tracked paths overflow the 8191-character cmd.exe
  // limit, and this has to work the same on Windows as in CI.
  hygiene: {
    args: ['scripts/hygiene.mjs', '--stdin-paths'],
    input: files.join('\n'),
  },
  secrets: { args: ['scripts/secretlint.mjs', '**/*'] },
  config: { args: [resolveBin('lefthook'), 'validate'] },
}

const requested = process.argv.slice(2)
const unknown = requested.filter((name) => !(name in CHECKS))
if (unknown.length > 0) {
  console.error(`Unknown guard: ${unknown.join(', ')}. Known: ${Object.keys(CHECKS).join(', ')}.`)
  process.exit(1)
}
const names = requested.length > 0 ? requested : Object.keys(CHECKS)

const failed = []

for (const name of names) {
  const { args, input } = CHECKS[name]
  console.log(`\n=== ${name} ===`)
  // Every check is a Node CLI spawned with the running Node binary, so no shell is involved and
  // nothing here needs quoting (see scripts/npm-bin.mjs).
  const result = spawnSync(node, args, {
    input,
    stdio: [input === undefined ? 'inherit' : 'pipe', 'inherit', 'inherit'],
  })
  if ((result.status ?? 1) !== 0) failed.push(name)
}

// Every check runs even after one fails: whoever has to fix this wants the whole list, not the first
// item of it.
if (failed.length > 0) {
  console.error(`\nGuards failed: ${failed.join(', ')}`)
  process.exitCode = 1
} else {
  console.log('\nGuards passed.')
}
