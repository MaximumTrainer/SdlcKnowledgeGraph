#!/usr/bin/env node
// The authorisation policy's tooling (#30, #95, docs/adr/0020): formats, tests and builds the Rego
// bundle under policy/ with Open Policy Agent.
//
//   node scripts/opa.mjs fmt     # every .rego file is formatted as `opa fmt` writes it
//   node scripts/opa.mjs test    # every Rego unit test passes (`opa test policy/ -v`)
//   node scripts/opa.mjs build   # compiles the bundle to WebAssembly, into the backend's resources
//   node scripts/opa.mjs check   # the committed bundle is what `build` would write now
//
// OPA runs from an image pinned by version and by digest, like promtool (scripts/promtool.mjs), so
// nobody installs it and every machine and CI run the same binary: Docker is already needed for the
// integration and acceptance suites. The digest is the checksum: a tag can be moved, a digest cannot.
//
// The API evaluates the bundle in its own process (ADR-0020), so what it enforces is exactly what
// `build` compiled. `check` is what keeps the committed bundle from drifting from the Rego beside it.
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const OPA_VERSION = '1.9.0'
export const OPA_IMAGE = `openpolicyagent/opa:${OPA_VERSION}-static@sha256:60b6af32b58377718546ac7d4634eecbfe50ec36f7d3ca3f8ebf515f9826c2ac`

export const POLICY_DIR = 'policy'
export const BUNDLE = 'backend/src/main/resources/policy/bundle.tar.gz'

/** What the API asks the compiled policy: one decision, a filter over a list, and the agent-actions policy. */
export const ENTRYPOINTS = ['sdlc/authz/decision', 'sdlc/authz/filter', 'sdlc/agent_actions/decision']

/** The opa arguments for a command, with the checkout mounted at /w and [out] (for build) at /out. */
export const plan = command => {
  switch (command) {
    case 'fmt':
      return [['fmt', '--list', '--fail', POLICY_DIR]]
    case 'test':
      return [['test', POLICY_DIR, '-v']]
    case 'build':
    case 'check':
      return [
        [
          'build',
          '--target',
          'wasm',
          ...ENTRYPOINTS.flatMap(entrypoint => ['--entrypoint', entrypoint]),
          // The tests are not part of the policy, and the bundle would change with every new one.
          '--ignore',
          '*_test.rego',
          '--bundle',
          POLICY_DIR,
          '--output',
          '/out/bundle.tar.gz',
        ],
      ]
    default:
      throw new Error(`unknown command '${command}'; use fmt, test, build or check`)
  }
}

/** The docker arguments that run opa from the pinned image, as the invoking user so outputs stay theirs. */
export const dockerArgs = (root, out, opaArgs, user = currentUser()) => [
  'run',
  '--rm',
  ...(user ? ['--user', user] : []),
  '-v',
  `${root}:/w:ro`,
  ...(out ? ['-v', `${out}:/out`] : []),
  '-w',
  '/w',
  OPA_IMAGE,
  ...opaArgs,
]

const currentUser = () =>
  typeof process.getuid === 'function' ? `${process.getuid()}:${process.getgid()}` : null

const run = args => {
  const { status, error } = spawnSync('docker', args, { stdio: 'inherit' })
  if (error) {
    console.error(`Could not run docker: ${error.message}. opa runs from ${OPA_IMAGE}.`)
    process.exit(1)
  }
  if (status !== 0) process.exit(status ?? 1)
}

const main = () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
  const command = process.argv[2]
  let invocations
  try {
    invocations = plan(command)
  } catch (error) {
    console.error(error.message)
    process.exit(1)
  }
  const needsOut = command === 'build' || command === 'check'
  const out = needsOut ? mkdtempSync(path.join(tmpdir(), 'skg-opa-')) : null
  try {
    for (const args of invocations) {
      console.log(`opa ${args.join(' ')}`)
      run(dockerArgs(root, out, args))
    }
    if (command === 'build') {
      mkdirSync(path.dirname(path.join(root, BUNDLE)), { recursive: true })
      writeFileSync(path.join(root, BUNDLE), readFileSync(path.join(out, 'bundle.tar.gz')))
      console.log(`wrote ${BUNDLE}`)
    }
    if (command === 'check') {
      const built = readFileSync(path.join(out, 'bundle.tar.gz'))
      let committed = null
      try {
        committed = readFileSync(path.join(root, BUNDLE))
      } catch {
        // A missing bundle is as stale as a different one.
      }
      if (!committed || !built.equals(committed)) {
        console.error(`${BUNDLE} is not what the Rego under ${POLICY_DIR}/ compiles to. Run node scripts/opa.mjs build and commit it.`)
        process.exit(1)
      }
      console.log(`${BUNDLE} is current`)
    }
  } finally {
    if (out) rmSync(out, { recursive: true, force: true })
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()
