import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { CONFLICT, createRepo } from './test-support.mjs'

/**
 * `guards` is the whole-repository run: what CI uses, because `LEFTHOOK=0` bypasses the hooks and a
 * bypass nothing re-checks is not a gate. Its own contract is small but load-bearing - run what was
 * asked for, refuse a name it does not know rather than silently checking nothing, and report every
 * failure so one fix does not have to be found at a time.
 */
describe('guards', () => {
  let repo

  before(() => {
    repo = createRepo()
    // secretlint.mjs always passes --secretlintignore; give the throwaway repository an empty one.
    repo.write('.secretlintignore', '')
  })
  after(() => repo.cleanup())

  const guards = names => repo.run('guards.mjs', names)

  test('runs only the guards it was asked for', () => {
    repo.stage('fine.txt', 'nothing wrong here\n')

    const { status, stdout } = guards(['hygiene'])

    assert.equal(status, 0)
    assert.match(stdout, /=== hygiene ===/)
    assert.doesNotMatch(stdout, /=== secrets ===/)
    assert.doesNotMatch(stdout, /=== config ===/)
  })

  test('rejects an unknown guard instead of quietly checking nothing', () => {
    const { status, stderr } = guards(['hygeine'])

    assert.equal(status, 1)
    assert.match(stderr, /Unknown guard: hygeine/)
    // Naming the known ones turns a typo into a one-line fix.
    assert.match(stderr, /Known: hygiene, secrets, config/)
  })

  test('rejects an unknown guard even when a known one is alongside it', () => {
    const { status, stdout } = guards(['hygiene', 'nope'])

    assert.equal(status, 1)
    assert.doesNotMatch(stdout, /=== hygiene ===/)
  })

  test('checks tracked files of the repository it is run in', () => {
    repo.stage(
      'broken.txt',
      [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
    )
    repo.commit('add a conflicted file (#65)')

    const { status, stderr } = guards(['hygiene'])

    assert.equal(status, 1)
    assert.match(stderr, /Guards failed: hygiene/)
  })

  /**
   * Whoever has to fix this wants the whole list, not the first item of it. A run that stopped at
   * the first failure would turn one bad commit into several rounds of push, wait, fix.
   */
  test('reports every failing guard, not only the first', () => {
    // Built at run time: a literal credential here would be found by the repository's own secrets
    // guard, which scans this file too.
    repo.stage('remote.conf', 'url = https://user:' + 'sup3r-s3cret-value' + '@example.com/repo.git')
    repo.commit('add a leaked credential (#65)')

    const { status, stderr } = guards(['hygiene', 'secrets'])

    assert.equal(status, 1)
    assert.match(stderr, /Guards failed: hygiene, secrets/)
  })
})
