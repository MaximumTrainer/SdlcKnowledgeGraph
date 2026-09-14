import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { resolveBin } from './npm-bin.mjs'
import { scriptPath } from './test-support.mjs'

/**
 * `resolveBin` is how every guard reaches its CLI. When it cannot resolve one it calls
 * `process.exit`, so the failure cases have to be observed from a child process - which is also how
 * the guards themselves invoke it.
 */
describe('npm-bin', () => {
  /** Imports npm-bin in a child process and calls resolveBin, so process.exit can be observed. */
  const attempt = (...args) =>
    spawnSync(
      process.execPath,
      [
        '--input-type=module',
        '-e',
        // A Windows path is not a URL the ESM loader accepts; it has to be a file:// one.
        `import { resolveBin } from ${JSON.stringify(pathToFileURL(scriptPath('npm-bin.mjs')).href)}
         console.log(resolveBin(${args.map(a => JSON.stringify(a)).join(', ')}))`,
      ],
      { encoding: 'utf8' }
    )

  test('resolves the entry point of an installed dependency', () => {
    const bin = resolveBin('secretlint')

    assert.ok(existsSync(bin), `resolveBin returned ${bin}, which does not exist`)
  })

  test('resolves a bin whose name differs from the package name', () => {
    assert.ok(existsSync(resolveBin('lefthook')))
  })

  test('exits with a readable message when the package is not installed', () => {
    const result = attempt('a-package-that-is-not-installed')

    assert.equal(result.status, 1)
    assert.match(result.stderr, /is not installed/)
    // The message has to say what to do, not only what went wrong.
    assert.match(result.stderr, /npm install/)
  })

  test('exits with a readable message when the package declares no bin at all', () => {
    const result = attempt('@commitlint/config-conventional')

    assert.equal(result.status, 1)
    assert.match(result.stderr, /its layout has changed/)
  })

  test('exits with a readable message when the named bin is gone from the map', () => {
    const result = attempt('lefthook', 'a-bin-lefthook-does-not-declare')

    assert.equal(result.status, 1)
    assert.match(result.stderr, /its layout has changed/)
  })

  /**
   * A package whose `bin` is a bare string declares exactly one entry point, and npm installs it
   * under the package name. There is no map to look a name up in, so the string is the answer - not
   * a miss to report. Pinned because the branch reads as if it ignores its argument by accident.
   */
  test('returns the single entry point when bin is a string, whatever name is asked for', () => {
    assert.equal(resolveBin('secretlint'), resolveBin('secretlint', 'any-name'))
  })
})
