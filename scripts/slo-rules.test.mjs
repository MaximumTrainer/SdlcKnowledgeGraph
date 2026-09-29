import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import yaml from 'js-yaml'
import { BURN_RATES, check, generate, parseObjectives, render } from './slo-rules.mjs'

/**
 * The generator that turns ops/slo.yaml into multi-window, multi-burn-rate alert rules (#44, FR8).
 * What it must get right: the four window pairs and their factors, a threshold that is the factor
 * times the error budget, a runbook on every alert, and refusing an objective it cannot render.
 */
describe('slo-rules', () => {
  const source = `
objectives:
  - name: availability
    description: Requests the API answers without a server error
    objective: 0.995
    window: 30d
    sli:
      good: sum by (job) (rate(requests_total{outcome!="SERVER_ERROR"}[$window]))
      total: sum by (job) (rate(requests_total[$window]))
    runbook: availability
`
  const objectives = parseObjectives(source)
  const rules = yaml.load(render(objectives))
  const group = rules.groups.find(g => g.name === 'slo-availability')
  const alert = name => group.rules.find(r => r.alert === name)

  test('declares the burn rates of the workbook: page at 14.4x and 6x, ticket at 3x and 1x', () => {
    assert.deepEqual(BURN_RATES, [
      { severity: 'page', factor: 14.4, long: '1h', short: '5m' },
      { severity: 'page', factor: 6, long: '6h', short: '30m' },
      { severity: 'ticket', factor: 3, long: '1d', short: '2h' },
      { severity: 'ticket', factor: 1, long: '3d', short: '6h' },
    ])
  })

  test('records the error ratio of each objective over every window a burn rate reads', () => {
    const recorded = group.rules.filter(r => r.record).map(r => [r.record, r.labels.slo])
    assert.deepEqual(
      recorded.map(([name]) => name),
      ['5m', '30m', '1h', '2h', '6h', '1d', '3d'].map(w => `sdlc:slo_errors:ratio_rate${w}`),
    )
    assert.ok(recorded.every(([, slo]) => slo === 'availability'))
    const fiveMinutes = group.rules.find(r => r.record === 'sdlc:slo_errors:ratio_rate5m').expr
    assert.match(fiveMinutes, /rate\(requests_total\{outcome!="SERVER_ERROR"\}\[5m\]\)/)
    assert.match(fiveMinutes, /^1 - \(/)
  })

  test('pages when the budget burns at 14.4x over 1h and 5m, or 6x over 6h and 30m', () => {
    const page = alert('AvailabilityBudgetFastBurn')
    assert.equal(page.labels.severity, 'page')
    assert.equal(page.labels.slo, 'availability')
    assert.match(page.expr, /ratio_rate1h\{slo="availability"\} > 0\.072 and .*ratio_rate5m\{slo="availability"\} > 0\.072/s)
    assert.match(page.expr, /ratio_rate6h\{slo="availability"\} > 0\.03 and .*ratio_rate30m\{slo="availability"\} > 0\.03/s)
  })

  test('opens a ticket when it burns at 3x over 1d and 2h, or 1x over 3d and 6h', () => {
    const ticket = alert('AvailabilityBudgetSlowBurn')
    assert.equal(ticket.labels.severity, 'ticket')
    assert.match(ticket.expr, /ratio_rate1d\{slo="availability"\} > 0\.015 and .*ratio_rate2h\{slo="availability"\} > 0\.015/s)
    assert.match(ticket.expr, /ratio_rate3d\{slo="availability"\} > 0\.005 and .*ratio_rate6h\{slo="availability"\} > 0\.005/s)
  })

  test('points every alert at its runbook and says what is happening', () => {
    for (const name of ['AvailabilityBudgetFastBurn', 'AvailabilityBudgetSlowBurn']) {
      assert.equal(
        alert(name).annotations.runbook_url,
        'https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/docs/runbooks/availability.md',
      )
      assert.match(alert(name).annotations.summary, /^The availability error budget is burning/)
    }
  })

  test('marks the output as generated, naming the command that regenerates it', () => {
    assert.match(render(objectives), /^# GENERATED FROM ops\/slo\.yaml - DO NOT EDIT\. Run node scripts\/slo-rules\.mjs\./)
  })

  test('refuses an objective it cannot render, naming each problem', () => {
    const bad = `
objectives:
  - name: availability
    objective: 1.5
    window: 30d
    sli: { good: "rate(x[5m])", total: "rate(y[$window])" }
  - name: availability
    objective: 0.99
    window: 30d
    sli: { good: "rate(x[$window])", total: "rate(y[$window])" }
    runbook: availability
`
    assert.throws(() => parseObjectives(bad), error => {
      assert.match(error.message, /objectives\[0\]\.objective must be between 0 and 1/)
      assert.match(error.message, /objectives\[0\]\.runbook is required/)
      assert.match(error.message, /objectives\[0\]\.sli\.good must read \[\$window\]/)
      assert.match(error.message, /objectives\[1\]\.name availability is declared twice/)
      return true
    })
  })

  test('check reports rules that do not match the objectives, and passes once regenerated', () => {
    const root = mkdtempSync(path.join(tmpdir(), 'skg-slo-'))
    mkdirSync(path.join(root, 'ops/alerts/generated'), { recursive: true })
    writeFileSync(path.join(root, 'ops/slo.yaml'), source)
    writeFileSync(path.join(root, 'ops/alerts/generated/slo.rules.yml'), 'groups: []\n')

    assert.deepEqual(check(root), ['ops/alerts/generated/slo.rules.yml'])
    generate(root)
    assert.deepEqual(check(root), [])
    assert.equal(readFileSync(path.join(root, 'ops/alerts/generated/slo.rules.yml'), 'utf8'), render(objectives))
  })
})
