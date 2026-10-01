import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { BUNDLE, ENTRYPOINTS, OPA_IMAGE, OPA_VERSION, POLICY_DIR, dockerArgs, plan } from './opa.mjs'

/**
 * What the policy tooling runs (#30, #95): opa from one image pinned by version and digest, formatting
 * and testing the Rego under policy/, and compiling it to the WebAssembly bundle the API evaluates.
 */
describe('opa', () => {
  test('runs a pinned release, by version and by digest', () => {
    assert.match(OPA_VERSION, /^\d+\.\d+\.\d+$/)
    assert.match(OPA_IMAGE, new RegExp(`^openpolicyagent/opa:${OPA_VERSION.replaceAll('.', '\\.')}-static@sha256:[0-9a-f]{64}$`))
  })

  test('fails formatting that opa fmt would change, and runs every Rego test', () => {
    assert.deepEqual(plan('fmt'), [['fmt', '--list', '--fail', 'policy']])
    assert.deepEqual(plan('test'), [['test', 'policy', '-v']])
  })

  test('builds every entrypoint the API asks into one WebAssembly bundle, leaving the tests out', () => {
    const [args] = plan('build')

    assert.deepEqual(args.slice(0, 3), ['build', '--target', 'wasm'])
    for (const entrypoint of ['sdlc/authz/decision', 'sdlc/authz/filter', 'sdlc/agent_actions/decision']) {
      assert.ok(args.join(' ').includes(`--entrypoint ${entrypoint}`), entrypoint)
    }
    assert.equal(ENTRYPOINTS.length, 3)
    assert.ok(args.join(' ').includes('--ignore *_test.rego'))
    assert.ok(args.join(' ').includes(`--bundle ${POLICY_DIR}`))
    assert.deepEqual(plan('check'), plan('build'))
    assert.equal(BUNDLE, 'backend/src/main/resources/policy/bundle.tar.gz')
  })

  test('refuses a command it does not know, naming the ones it does', () => {
    assert.throws(() => plan('eval'), /use fmt, test, build or check/)
  })

  test('mounts the checkout read-only, and the output directory only when there is one', () => {
    assert.deepEqual(dockerArgs('/repo', null, ['test', 'policy'], '1000:1000'), [
      'run', '--rm', '--user', '1000:1000', '-v', '/repo:/w:ro', '-w', '/w', OPA_IMAGE, 'test', 'policy',
    ])
    assert.deepEqual(dockerArgs('/repo', '/tmp/out', ['build'], null), [
      'run', '--rm', '-v', '/repo:/w:ro', '-v', '/tmp/out:/out', '-w', '/w', OPA_IMAGE, 'build',
    ])
  })
})
