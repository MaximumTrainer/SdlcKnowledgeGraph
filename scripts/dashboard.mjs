#!/usr/bin/env node
// Checks the Grafana dashboard under ops/grafana (#29, FR9) against what the API publishes.
//
// A panel whose query names a metric that does not exist draws an empty graph, which looks exactly
// like a quiet system. So every metric a panel's PromQL reads must be in the Metrics table of
// docs/OBSERVABILITY.md (a histogram's _bucket, _count and _sum series count as the histogram), and
// every panel must query the provisioned Prometheus datasource. That keeps the docs and the
// dashboard from drifting: a meter renamed in one place fails here until the other says so too.
//
// Then, unless --static is given, every query is wrapped as a recording rule and handed to
// `promtool check rules` from the pinned Prometheus image, so a query that does not parse fails
// here rather than in front of whoever opened the dashboard.
//
// Usage: node scripts/dashboard.mjs [--static]
import { spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'
import { PROMETHEUS_IMAGE, dockerArgs } from './promtool.mjs'

export const DASHBOARD = 'ops/grafana/dashboards/sdlc-sync-dashboard.json'
export const DATASOURCES = 'ops/grafana/provisioning/datasources/prometheus.yml'
export const DOCS = 'docs/OBSERVABILITY.md'

/** Words PromQL reserves, which look like metric names to a tokenizer that does not know them. */
const KEYWORDS = new Set([
  'by', 'without', 'on', 'ignoring', 'group_left', 'group_right', 'bool', 'and', 'or', 'unless', 'offset', 'inf', 'nan',
])

/** The series a histogram exposes beside its buckets' base name. */
const HISTOGRAM_SERIES = /^(.+)_(bucket|count|sum)$/

/**
 * The metric names a PromQL query reads, sorted. Strings, label matchers, ranges and label lists are
 * blanked first, so what is left that looks like a name and is not followed by `(` is a metric.
 */
export const metricNames = expr => {
  const names = new Set([...expr.matchAll(/__name__\s*=\s*"([^"]+)"/g)].map(match => match[1]))
  const bare = expr
    .replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`[^`]*`/g, '""')
    .replace(/\{[^}]*\}/g, ' ')
    .replace(/\[[^\]]*\]/g, ' ')
    .replace(/\b(by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)/g, ' ')
  for (const [, name, call] of bare.matchAll(/(?<![\w:.$])([a-zA-Z_:][\w:]*)(?![\w:])(\s*\()?/g)) {
    if (!call && !KEYWORDS.has(name.toLowerCase())) names.add(name)
  }
  return [...names].sort()
}

/** The metric names in the first column of the Metrics table in docs/OBSERVABILITY.md. */
export const documentedMetrics = markdown => {
  const section = markdown.split(/^## /m).find(part => part.startsWith('Metrics\n')) ?? ''
  const names = section
    .split(/\r?\n/)
    .map(line => line.match(/^\|\s*`([a-zA-Z_:][\w:]*)`\s*\|/)?.[1])
    .filter(Boolean)
  if (names.length === 0) throw new Error(`no Metrics table in ${DOCS}`)
  return new Set(names)
}

/** Every panel that draws something, those inside a collapsed row included, in order. */
const drawnPanels = dashboard =>
  (dashboard.panels ?? []).flatMap(panel => (panel.type === 'row' ? (panel.panels ?? []) : [panel]))

/** What is wrong with a dashboard, as sentences naming the panel; empty when nothing is. */
export const checkDashboard = (dashboard, { documented, datasource }) => {
  const problems = []
  if (!dashboard.uid) problems.push('the dashboard has no uid')
  const panels = drawnPanels(dashboard)
  if (panels.length === 0) problems.push('the dashboard has no panels')

  const isDocumented = name => documented.has(name) || documented.has(name.match(HISTOGRAM_SERIES)?.[1])
  for (const panel of panels) {
    const targets = panel.targets ?? []
    if (targets.length === 0) {
      problems.push(`panel "${panel.title}" has no query`)
      continue
    }
    const uids = [panel.datasource, ...targets.map(target => target.datasource)].filter(Boolean).map(ds => ds.uid)
    if (uids.length === 0 || uids.some(uid => uid !== datasource)) {
      problems.push(`panel "${panel.title}" does not use the provisioned datasource "${datasource}"`)
    }
    for (const name of targets.flatMap(target => metricNames(target.expr ?? ''))) {
      if (!isDocumented(name)) problems.push(`panel "${panel.title}" reads ${name}, which ${DOCS} does not list`)
    }
  }
  return problems
}

/**
 * Every query as a recording rule, as YAML promtool can check. Grafana's interval variables are not
 * PromQL, so they are replaced with a concrete range first.
 */
export const recordingRules = dashboard => {
  const rules = drawnPanels(dashboard).flatMap((panel, index) =>
    (panel.targets ?? []).map(target => ({
      record: `dashboard:panel_${index + 1}_${target.refId}`,
      expr: target.expr.replace(/\$\{?__(rate_interval|interval|range)\}?/g, '5m'),
    })),
  )
  return yaml.dump({ groups: [{ name: 'dashboard', rules }] }, { lineWidth: -1 })
}

const main = () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const read = file => readFileSync(path.join(root, file), 'utf8')

  let dashboard
  let problems
  try {
    dashboard = JSON.parse(read(DASHBOARD))
    const datasource = yaml.load(read(DATASOURCES)).datasources.find(source => source.type === 'prometheus')?.uid
    problems = checkDashboard(dashboard, { documented: documentedMetrics(read(DOCS)), datasource })
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
  if (problems.length > 0) {
    problems.forEach(problem => console.error(`${DASHBOARD}: ${problem}`))
    process.exit(1)
  }
  console.log(`${DASHBOARD}: every panel reads documented metrics`)
  if (process.argv.includes('--static')) return

  // Written outside the checkout, so a failed run leaves nothing behind for a commit to pick up.
  const dir = mkdtempSync(path.join(tmpdir(), 'skg-dashboard-'))
  try {
    // mkdtemp makes the directory private to this user, and promtool runs as nobody in the image.
    chmodSync(dir, 0o755)
    writeFileSync(path.join(dir, 'dashboard.rules.yml'), recordingRules(dashboard))
    const args = ['check', 'rules', 'dashboard.rules.yml']
    console.log(`promtool ${args.join(' ')}`)
    const { status, error } = spawnSync('docker', dockerArgs(dir, args), { stdio: 'inherit' })
    if (error) {
      console.error(`Could not run docker: ${error.message}. promtool runs from ${PROMETHEUS_IMAGE}.`)
      process.exitCode = 1
    } else if (status !== 0) {
      process.exitCode = status ?? 1
    }
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
