#!/usr/bin/env node
// Checks and tests the alert rules under ops/alerts with promtool (#44, FR11), and the Alertmanager
// routing under ops/alertmanager with amtool (FR12).
//
// Both tools run from pinned images rather than from a local install, so nobody has to install
// Prometheus: Docker is already needed for the integration and acceptance suites. It checks every
// rule file, then runs every test file under ops/alerts/tests, each of which says in which cases an
// alert fires and in which it does not. Then it checks the Alertmanager config and resolves every
// case in ops/alertmanager/tests/routes.test.yml to the receiver the case expects.
//
// Usage: node scripts/promtool.mjs
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import yaml from 'js-yaml'

export const PROMETHEUS_IMAGE = 'prom/prometheus:v3.5.0'
export const ALERTMANAGER_IMAGE = 'prom/alertmanager:v0.28.1'

const RULES = 'ops/alerts'
const TESTS = 'ops/alerts/tests'
export const ALERTMANAGER_CONFIG = 'ops/alertmanager/alertmanager.yml'
const ROUTE_TESTS = 'ops/alertmanager/tests/routes.test.yml'

/** Every .yml file under a directory, as forward-slash paths relative to the root, sorted. */
const yamlFiles = (root, dir, { recurse, skip = [] }) => {
  const absolute = path.join(root, dir)
  if (!existsSync(absolute)) return []
  return readdirSync(absolute, { withFileTypes: true })
    .flatMap(entry => {
      const relative = `${dir}/${entry.name}`
      if (entry.isDirectory()) return recurse && !skip.includes(relative) ? yamlFiles(root, relative, { recurse, skip }) : []
      return entry.name.endsWith('.yml') ? [relative] : []
    })
    .sort()
}

/** The promtool invocations for a checkout: one `check rules` over every rule file, one `test rules`. */
export const plan = root => {
  const rules = yamlFiles(root, RULES, { recurse: true, skip: [TESTS] })
  const tests = yamlFiles(root, TESTS, { recurse: false }).filter(file => file.endsWith('.test.yml'))
  if (rules.length === 0) throw new Error(`no rule files under ${RULES}`)
  if (tests.length === 0) throw new Error(`no test files under ${TESTS}`)
  return [
    ['check', 'rules', ...rules],
    ['test', 'rules', ...tests],
  ]
}

/**
 * The amtool invocations for a checkout: one `check-config`, then one `config routes test` per case
 * in the route tests, which exits non-zero when the alert resolves to another receiver.
 */
export const routePlan = root => {
  if (!existsSync(path.join(root, ALERTMANAGER_CONFIG))) throw new Error(`no Alertmanager config at ${ALERTMANAGER_CONFIG}`)
  const testFile = path.join(root, ROUTE_TESTS)
  const cases = existsSync(testFile) ? (yaml.load(readFileSync(testFile, 'utf8'))?.cases ?? []) : []
  if (cases.length === 0) throw new Error(`no route cases in ${ROUTE_TESTS}`)
  return [
    ['check-config', ALERTMANAGER_CONFIG],
    ...cases.map(({ labels, receiver }) => [
      'config',
      'routes',
      'test',
      `--config.file=${ALERTMANAGER_CONFIG}`,
      `--verify.receivers=${receiver}`,
      ...Object.entries(labels).map(([name, value]) => `${name}=${value}`),
    ]),
  ]
}

/** The docker arguments that run a tool from an image with the checkout mounted at /w. */
export const dockerArgs = (root, toolArgs, { image = PROMETHEUS_IMAGE, tool = 'promtool' } = {}) => [
  'run',
  '--rm',
  '-v',
  `${root}:/w`,
  '-w',
  '/w',
  '--entrypoint',
  tool,
  image,
  ...toolArgs,
]

const main = () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  let invocations
  try {
    const promtool = { image: PROMETHEUS_IMAGE, tool: 'promtool' }
    const amtool = { image: ALERTMANAGER_IMAGE, tool: 'amtool' }
    invocations = [
      ...plan(root).map(args => ({ ...promtool, args })),
      ...routePlan(root).map(args => ({ ...amtool, args })),
    ]
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
  for (const { image, tool, args } of invocations) {
    console.log(`${tool} ${args.join(' ')}`)
    const { status, error } = spawnSync('docker', dockerArgs(root, args, { image, tool }), { stdio: 'inherit' })
    if (error) {
      console.error(`Could not run docker: ${error.message}. ${tool} runs from ${image}.`)
      process.exit(1)
    }
    if (status !== 0) process.exit(status ?? 1)
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
