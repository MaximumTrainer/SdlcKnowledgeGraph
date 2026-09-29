import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { PROMETHEUS_IMAGE, dockerArgs, plan } from './promtool.mjs'

/**
 * What the promtool wrapper runs (#44, FR11): every rule file checked, every test file run, from the
 * pinned image. A directory with no rules or no tests is an error, not a pass.
 */
describe('promtool', () => {
  const checkout = files => {
    const root = mkdtempSync(path.join(tmpdir(), 'skg-promtool-'))
    for (const file of files) {
      mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
      writeFileSync(path.join(root, file), 'groups: []\n')
    }
    return root
  }

  test('checks every rule file, generated ones included, and runs every test file', () => {
    const root = checkout([
      'ops/alerts/app.rules.yml',
      'ops/alerts/generated/slo.rules.yml',
      'ops/alerts/tests/app.test.yml',
      'ops/alerts/tests/slo.test.yml',
    ])

    assert.deepEqual(plan(root), [
      ['check', 'rules', 'ops/alerts/app.rules.yml', 'ops/alerts/generated/slo.rules.yml'],
      ['test', 'rules', 'ops/alerts/tests/app.test.yml', 'ops/alerts/tests/slo.test.yml'],
    ])
  })

  test('refuses a checkout with rules but no tests', () => {
    assert.throws(() => plan(checkout(['ops/alerts/app.rules.yml'])), /no test files under ops\/alerts\/tests/)
  })

  test('runs promtool from the pinned image with the checkout mounted', () => {
    assert.deepEqual(dockerArgs('/repo', ['test', 'rules', 'a.yml']), [
      'run', '--rm', '-v', '/repo:/w', '-w', '/w', '--entrypoint', 'promtool', PROMETHEUS_IMAGE, 'test', 'rules', 'a.yml',
    ])
    assert.match(PROMETHEUS_IMAGE, /^prom\/prometheus:v\d+\.\d+\.\d+$/)
  })
})
