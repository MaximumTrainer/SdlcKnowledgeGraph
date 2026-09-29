#!/usr/bin/env node
// Checks and tests the alert rules under ops/alerts with promtool (#44, FR11).
//
// promtool runs from the pinned Prometheus image rather than from a local install, so nobody has to
// install Prometheus: Docker is already needed for the integration and acceptance suites. It checks
// every rule file, then runs every test file under ops/alerts/tests, each of which says in which
// cases an alert fires and in which it does not.
//
// Usage: node scripts/promtool.mjs
import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const PROMETHEUS_IMAGE = 'prom/prometheus:v3.5.0'

const RULES = 'ops/alerts'
const TESTS = 'ops/alerts/tests'

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

/** The docker arguments that run promtool with the checkout mounted at /w. */
export const dockerArgs = (root, promtoolArgs) => [
  'run',
  '--rm',
  '-v',
  `${root}:/w`,
  '-w',
  '/w',
  '--entrypoint',
  'promtool',
  PROMETHEUS_IMAGE,
  ...promtoolArgs,
]

const main = () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  let invocations
  try {
    invocations = plan(root)
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
  for (const args of invocations) {
    console.log(`promtool ${args.join(' ')}`)
    const { status, error } = spawnSync('docker', dockerArgs(root, args), { stdio: 'inherit' })
    if (error) {
      console.error(`Could not run docker: ${error.message}. promtool runs from ${PROMETHEUS_IMAGE}.`)
      process.exit(1)
    }
    if (status !== 0) process.exit(status ?? 1)
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
