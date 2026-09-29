import { test, describe, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { scriptPath } from './test-support.mjs'

/**
 * The actionlint wrapper, which the pre-commit hook runs on every staged workflow.
 *
 * The npm package is a 2022 WebAssembly build of actionlint. Anything GitHub added since is unknown to
 * it, and a finding about a real feature blocks the commit that uses it. The wrapper knows the one
 * such finding this repository meets, and nothing wider: an unknown scope is still refused.
 */
describe('actionlint', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'skg-actionlint-'))
  after(() => rmSync(dir, { recursive: true, force: true }))

  const lint = (permissions) => {
    const file = path.join(dir, 'workflow.yml')
    writeFileSync(
      file,
      `on: push
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
${permissions.map(p => `      ${p}`).join('\n')}
    steps:
      - run: echo hello
`,
    )
    return spawnSync(process.execPath, [scriptPath('actionlint.mjs'), file], { encoding: 'utf8' })
  }

  test('accepts the attestations scope, which GitHub added after the linter was built', () => {
    const result = lint(['contents: read', 'attestations: write'])

    assert.equal(result.status, 0, result.stderr)
  })

  test('still refuses a permission scope that does not exist', () => {
    const result = lint(['contents: read', 'no-such-scope: write'])

    assert.equal(result.status, 1)
    assert.match(result.stderr, /no-such-scope/)
  })
})
