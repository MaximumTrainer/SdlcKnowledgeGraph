import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createRepo } from './test-support.mjs'

/**
 * `protected-branch` is what keeps `main` moving only through a merge. Its two false positives both
 * break ordinary work - a rebase runs on a detached HEAD, and a feature branch must always be
 * allowed - so they are pinned alongside the refusal.
 */
describe('protected-branch', () => {
  let repo

  before(() => {
    repo = createRepo()
    repo.commit('root commit (#65)')
  })
  after(() => repo.cleanup())

  const guard = (action = 'commit', env) => repo.run('protected-branch.mjs', [action], { env })

  test('refuses a commit while a protected branch is checked out', () => {
    repo.git('switch', '--quiet', '--create', 'main')

    const { status, stderr } = guard('commit')

    assert.equal(status, 1)
    assert.match(stderr, /Refusing to commit on 'main'/)
    // The way out has to be discoverable at the moment it is needed.
    assert.match(stderr, /ALLOW_MAIN=1/)
  })

  test('names the action it refused, so the push message does not say commit', () => {
    repo.git('switch', '--quiet', 'main')

    assert.match(guard('push').stderr, /Refusing to push on 'main'/)
  })

  test('allows a commit on a feature branch', () => {
    repo.git('switch', '--quiet', '--create', 'feat/65-something')

    assert.equal(guard('commit').status, 0)
  })

  test('allows a detached HEAD, so a rebase or a bisect is not blocked', () => {
    repo.git('switch', '--quiet', '--detach')

    assert.equal(guard('commit').status, 0)
  })

  test('honours ALLOW_MAIN as the named escape hatch', () => {
    repo.git('switch', '--quiet', 'main')

    assert.equal(guard('commit', { ALLOW_MAIN: '1' }).status, 0)
    assert.equal(guard('commit', { ALLOW_MAIN: 'true' }).status, 0)
  })

  test('ignores an ALLOW_MAIN that was not set to a yes', () => {
    repo.git('switch', '--quiet', 'main')

    assert.equal(guard('commit', { ALLOW_MAIN: '0' }).status, 1)
  })

  test('protects master as well as main', () => {
    repo.git('switch', '--quiet', '--create', 'master')

    assert.equal(guard('commit').status, 1)
  })

  test('takes the protected list from PROTECTED_BRANCHES when it is set', () => {
    repo.git('switch', '--quiet', '--create', 'release')

    assert.equal(guard('commit', { PROTECTED_BRANCHES: 'release' }).status, 1)
    assert.equal(guard('commit', { PROTECTED_BRANCHES: 'main' }).status, 0)
  })
})
