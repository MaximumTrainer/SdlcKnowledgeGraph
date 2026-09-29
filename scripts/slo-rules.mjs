#!/usr/bin/env node
// Renders ops/slo.yaml into multi-window, multi-burn-rate alert rules (#44, FR8).
//
// Each objective gets recording rules for its error ratio over every window a burn rate reads, a page
// alert and a ticket alert. The burn rates are the Google SRE workbook's: page when the budget burns
// at 14.4x over 1h (confirmed over 5m) or 6x over 6h (30m), open a ticket at 3x over 1d (2h) or 1x
// over 3d (6h). The output is committed, and `--check` fails while it differs from what the
// objectives would render, so an objective and its alerts cannot be changed apart.
//
// Usage: node scripts/slo-rules.mjs          write ops/alerts/generated/slo.rules.yml
//        node scripts/slo-rules.mjs --check  fail if it is stale
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

export const SLO_FILE = 'ops/slo.yaml'
export const RULES_FILE = 'ops/alerts/generated/slo.rules.yml'
const RUNBOOKS = 'https://github.com/MaximumTrainer/SdlcKnowledgeGraph/blob/main/docs/runbooks'
const WINDOW = '$window'
const RECORD = 'sdlc:slo_errors:ratio_rate'

export const BURN_RATES = [
  { severity: 'page', factor: 14.4, long: '1h', short: '5m' },
  { severity: 'page', factor: 6, long: '6h', short: '30m' },
  { severity: 'ticket', factor: 3, long: '1d', short: '2h' },
  { severity: 'ticket', factor: 1, long: '3d', short: '6h' },
]

const SUMMARIES = {
  page: name => `The ${name} error budget is burning fast enough to be gone within days`,
  ticket: name => `The ${name} error budget is burning faster than it can last the window`,
}

const ALERT_SUFFIX = { page: 'BudgetFastBurn', ticket: 'BudgetSlowBurn' }

/** Every window a burn rate reads, shortest first. */
const WINDOWS = [...new Set(BURN_RATES.flatMap(rate => [rate.long, rate.short]))].sort((a, b) => seconds(a) - seconds(b))

function seconds(duration) {
  const unit = { m: 60, h: 3600, d: 86400 }[duration.slice(-1)]
  return Number(duration.slice(0, -1)) * unit
}

/** The objectives in a slo.yaml, or an error naming every problem with them. */
export const parseObjectives = text => {
  const objectives = yaml.load(text)?.objectives
  if (!Array.isArray(objectives) || objectives.length === 0) throw new Error(`${SLO_FILE} declares no objectives`)
  const problems = []
  const seen = new Set()
  objectives.forEach((objective, index) => {
    const at = `objectives[${index}]`
    if (!/^[a-z][a-z0-9-]*$/.test(objective.name ?? '')) problems.push(`${at}.name must be lower-case letters, digits and dashes`)
    else if (seen.has(objective.name)) problems.push(`${at}.name ${objective.name} is declared twice`)
    seen.add(objective.name)
    if (!(typeof objective.objective === 'number' && objective.objective > 0 && objective.objective < 1)) {
      problems.push(`${at}.objective must be between 0 and 1`)
    }
    if (!/^\d+[mhd]$/.test(objective.window ?? '')) problems.push(`${at}.window must be a duration such as 30d`)
    for (const side of ['good', 'total']) {
      if (!String(objective.sli?.[side] ?? '').includes(`[${WINDOW}]`)) problems.push(`${at}.sli.${side} must read [${WINDOW}]`)
    }
    if (!/^[a-z0-9-]+$/.test(objective.runbook ?? '')) problems.push(`${at}.runbook is required: the name of a file under docs/runbooks, without .md`)
  })
  if (problems.length > 0) throw new Error(`${SLO_FILE} is invalid:\n  ${problems.join('\n  ')}`)
  return objectives
}

const pascal = name => name.split('-').map(part => part[0].toUpperCase() + part.slice(1)).join('')

/** The factor times the error budget, without floating-point noise such as 0.0050000000000000044. */
const threshold = (factor, objective) => Number((factor * (1 - objective)).toPrecision(6))

const recordingRules = objective =>
  WINDOWS.map(window => ({
    record: `${RECORD}${window}`,
    expr: `1 - (\n  ${objective.sli.good.replaceAll(WINDOW, window)}\n/\n  ${objective.sli.total.replaceAll(WINDOW, window)}\n)`,
    labels: { slo: objective.name },
  }))

const alertRule = (objective, severity) => {
  const ratio = window => `${RECORD}${window}{slo="${objective.name}"}`
  const conditions = BURN_RATES.filter(rate => rate.severity === severity).map(rate => {
    const over = threshold(rate.factor, objective.objective)
    return `(${ratio(rate.long)} > ${over} and ${ratio(rate.short)} > ${over})`
  })
  return {
    alert: `${pascal(objective.name)}${ALERT_SUFFIX[severity]}`,
    expr: conditions.join('\nor\n'),
    labels: { severity, slo: objective.name },
    annotations: {
      summary: SUMMARIES[severity](objective.name),
      runbook_url: `${RUNBOOKS}/${objective.runbook}.md`,
    },
  }
}

/** The rules file for a set of objectives. */
export const render = objectives => {
  const groups = objectives.map(objective => ({
    name: `slo-${objective.name}`,
    rules: [...recordingRules(objective), alertRule(objective, 'page'), alertRule(objective, 'ticket')],
  }))
  const header = [
    `# GENERATED FROM ${SLO_FILE} - DO NOT EDIT. Run node scripts/slo-rules.mjs.`,
    '#',
    '# Multi-window, multi-burn-rate alerts for each service objective (docs/OBSERVABILITY.md).',
  ].join('\n')
  return `${header}\n${yaml.dump({ groups }, { lineWidth: -1, noRefs: true, sortKeys: false })}`
}

const renderFrom = root => render(parseObjectives(readFileSync(path.join(root, SLO_FILE), 'utf8')))

/** Writes the rules file for the objectives in a checkout. */
export const generate = root => {
  const target = path.join(root, RULES_FILE)
  mkdirSync(path.dirname(target), { recursive: true })
  writeFileSync(target, renderFrom(root))
}

/** The generated files that differ from what the objectives render: empty when all is current. */
export const check = root => {
  const target = path.join(root, RULES_FILE)
  const current = existsSync(target) ? readFileSync(target, 'utf8') : null
  return current === renderFrom(root) ? [] : [RULES_FILE]
}

const main = () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  try {
    if (process.argv.includes('--check')) {
      const stale = check(root)
      if (stale.length > 0) {
        console.error(`SLO alert rules are stale: ${stale.join(', ')}. Run node scripts/slo-rules.mjs and commit the result.`)
        process.exit(1)
      }
      return
    }
    generate(root)
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
