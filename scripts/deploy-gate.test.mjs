import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { decide } from './deploy-gate.mjs'

/**
 * The rule every deploy follows (#48, FR9; docs/DEPLOYMENT.md D12): only a green CI run of a push to
 * `main` is deployed, and what is deployed is that run's own commit.
 */
describe('deploy-gate', () => {
  const sha = '5efa09d68706304efec8ec74349dd72ed44912cb'
  const green = { conclusion: 'success', branch: 'main', event: 'push', sha, environment: 'dogfood' }

  test('deploys the commit a green push run on main tested', () => {
    assert.deepEqual(decide(green), {
      shouldDeploy: true,
      sha,
      imageTag: sha,
      reason: `CI passed on main at ${sha}: deploying it to dogfood`,
    })
  })

  test('prefixes the image tag when asked', () => {
    assert.equal(decide({ ...green, imageTagPrefix: 'dogfood-' }).imageTag, `dogfood-${sha}`)
  })

  for (const [what, change, reason] of [
    ['a red run', { conclusion: 'failure' }, /CI concluded failure/],
    ['a cancelled run', { conclusion: 'cancelled' }, /CI concluded cancelled/],
    ['a run on another branch', { branch: 'feature' }, /ran on feature, not main/],
    ['a pull request run', { event: 'pull_request' }, /was a pull_request, not a push/],
  ]) {
    test(`does not deploy ${what}`, () => {
      const decision = decide({ ...green, ...change })
      assert.equal(decision.shouldDeploy, false)
      assert.equal(decision.sha, '')
      assert.match(decision.reason, reason)
    })
  }

  test('refuses a commit that is not a full sha rather than deploying something else', () => {
    assert.throws(() => decide({ ...green, sha: 'main' }), /not a full commit sha/)
    assert.throws(() => decide({ ...green, sha: '' }), /not a full commit sha/)
  })

  test('refuses to run without an environment', () => {
    assert.throws(() => decide({ ...green, environment: '' }), /environment/)
  })
})
