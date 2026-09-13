import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRepo } from './test-support.mjs'

/**
 * The `secrets` guard is the one whose miss cannot be undone: a committed credential has to be
 * rotated, whatever happens to the commit afterwards.
 *
 * Credentials are built from pieces at run time throughout. A literal one here would be found by
 * this repository's own secrets guard, which scans this file too.
 */
const basicAuthUrl = () => 'url = https://user:' + 'sup3r-s3cret-value' + '@example.com/repo.git'

describe('secretlint', () => {
  let repo

  before(() => {
    repo = createRepo()
  })
  after(() => repo.cleanup())

  const scan = paths => repo.run('secretlint.mjs', paths)

  test('refuses a file holding a credential', () => {
    repo.stage('leaky.conf', basicAuthUrl())

    // Exactly 1: secretlint exits 1 on a finding and 2 when it cannot run at all, and a test that
    // accepted any non-zero status would pass just as happily against a broken configuration.
    assert.equal(scan(['leaky.conf']).status, 1)
  })

  test('allows a file holding none', () => {
    repo.stage('clean.conf', 'url = https://example.com/repo.git')

    assert.equal(scan(['clean.conf']).status, 0)
  })

  test('passes when given nothing to scan', () => {
    assert.equal(scan([]).status, 0)
  })

  /**
   * .hygieneignore forgives a credential-shaped *name*. If it also exempted the *content*, it would
   * become the documented way to smuggle a credential past the gate - so the two guards are checked
   * against the same file here rather than trusted to stay independent (#64).
   */
  test('still scans a file whose name hygiene has been told to allow', () => {
    repo.write('.hygieneignore', '.npmrc')
    repo.stage('.npmrc', basicAuthUrl())

    assert.equal(repo.run('hygiene.mjs', ['.npmrc']).status, 0, 'hygiene should allow the name')
    assert.equal(scan(['.npmrc']).status, 1, 'secretlint should still refuse the content')
  })
})
