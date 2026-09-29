import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { ALERTMANAGER_IMAGE, PROMETHEUS_IMAGE, dockerArgs, plan, routePlan } from './promtool.mjs'

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

  test('checks the Alertmanager config, then resolves every route case to its expected receiver', () => {
    const root = checkout([])
    mkdirSync(path.join(root, 'ops/alertmanager/tests'), { recursive: true })
    writeFileSync(path.join(root, 'ops/alertmanager/alertmanager.yml'), 'route: {}\n')
    writeFileSync(
      path.join(root, 'ops/alertmanager/tests/routes.test.yml'),
      'cases:\n  - name: pages\n    labels: { alertname: A, severity: page }\n    receiver: page\n',
    )

    assert.deepEqual(routePlan(root), [
      ['check-config', 'ops/alertmanager/alertmanager.yml'],
      [
        'config', 'routes', 'test', '--config.file=ops/alertmanager/alertmanager.yml', '--verify.receivers=page',
        'alertname=A', 'severity=page',
      ],
    ])
  })

  test('refuses an Alertmanager config with no route cases', () => {
    const root = checkout([])
    mkdirSync(path.join(root, 'ops/alertmanager'), { recursive: true })
    writeFileSync(path.join(root, 'ops/alertmanager/alertmanager.yml'), 'route: {}\n')
    assert.throws(() => routePlan(root), /no route cases in ops\/alertmanager\/tests\/routes.test.yml/)
  })

  test('runs amtool from the pinned Alertmanager image', () => {
    assert.deepEqual(dockerArgs('/repo', ['check-config', 'a.yml'], { image: ALERTMANAGER_IMAGE, tool: 'amtool' }), [
      'run', '--rm', '-v', '/repo:/w', '-w', '/w', '--entrypoint', 'amtool', ALERTMANAGER_IMAGE, 'check-config', 'a.yml',
    ])
    assert.match(ALERTMANAGER_IMAGE, /^prom\/alertmanager:v\d+\.\d+\.\d+$/)
  })
})
