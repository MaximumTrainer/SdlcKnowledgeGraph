import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readdirSync, realpathSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createRepo } from './test-support.mjs'

/**
 * A temp directory private to one test, and the environment that points a child process at it.
 *
 * `node --test` runs test files in parallel, and merge-guards.test.mjs also runs
 * `secretlint.mjs --staged` through a real hook. Asserting against the shared `os.tmpdir()` sees
 * that other run's scratch copy and fails for a reason that has nothing to do with this guard
 * (#123). `os.tmpdir()` reads TMPDIR on POSIX and TEMP/TMP on Windows, so setting all three is what
 * makes the child's scratch directory land somewhere only this test looks at.
 */
const privateTmpdir = () => {
  const home = mkdtempSync(path.join(realpathSync(tmpdir()), 'skg-scratch-'))
  return { home, env: { TMPDIR: home, TEMP: home, TMP: home } }
}

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
   * The hook exists to refuse a credential *before* it is in a commit, and what gets committed is
   * the index. Scanning the working tree judges the wrong bytes whenever the two differ - stage a
   * file holding a token, tidy the working copy before committing, and the token goes in while the
   * gate passes (#62).
   */
  describe('--staged', () => {
    let staged

    before(() => {
      staged = createRepo()
    })
    after(() => staged.cleanup())

    const scanStaged = paths => staged.run('secretlint.mjs', ['--staged', ...paths])

    test('refuses a secret that is staged but no longer in the working tree', () => {
      staged.stage('tidied.conf', basicAuthUrl())
      staged.write('tidied.conf', 'url = https://example.com/repo.git')

      assert.equal(scanStaged(['tidied.conf']).status, 1)
    })

    test('allows a clean index even when the working tree holds a secret', () => {
      staged.stage('later.conf', 'url = https://example.com/repo.git')
      staged.write('later.conf', basicAuthUrl())

      assert.equal(scanStaged(['later.conf']).status, 0)
    })

    test('reports the repository path, not the temporary one it scanned', () => {
      staged.stage('nested/deep/leak.conf', basicAuthUrl())

      const { stdout, stderr } = scanStaged(['nested/deep/leak.conf'])
      const output = stdout + stderr

      assert.match(output, /nested[/\\]deep[/\\]leak\.conf/)
      assert.doesNotMatch(output, /secretlint-staged-/)
    })

    test('skips a path the index cannot resolve, such as one being deleted', () => {
      staged.stage('doomed.conf', 'url = https://example.com/repo.git')
      staged.git('rm', '--quiet', '--cached', '--', 'doomed.conf')
      staged.stage('kept.conf', 'url = https://example.com/repo.git')

      assert.equal(scanStaged(['doomed.conf', 'kept.conf']).status, 0)
    })

    test('still finds a secret alongside a path the index cannot resolve', () => {
      staged.stage('gone.conf', 'nothing')
      staged.git('rm', '--quiet', '--cached', '--', 'gone.conf')
      staged.stage('present.conf', basicAuthUrl())

      assert.equal(scanStaged(['gone.conf', 'present.conf']).status, 1)
    })

    test('scans a renamed file under the name it will be committed as', () => {
      staged.stage('before-rename.conf', basicAuthUrl())
      staged.commit('add a file to rename (#62)')
      staged.git('mv', 'before-rename.conf', 'after-rename.conf')

      assert.equal(scanStaged(['after-rename.conf']).status, 1)
    })

    test('passes when nothing is staged', () => {
      assert.equal(scanStaged([]).status, 0)
    })

    /**
     * The scratch copy holds the very credential being refused, so it has to go on every exit -
     * including the one where secretlint found something, which is the path a `finally` is easiest
     * to get wrong on. `process.exit` would skip it entirely.
     */
    test('leaves no temporary directory behind, on a finding or a pass', () => {
      const { home, env } = privateTmpdir()

      try {
        staged.stage('leftover.conf', basicAuthUrl())
        assert.equal(staged.run('secretlint.mjs', ['--staged', 'leftover.conf'], { env }).status, 1)

        staged.stage('tidy.conf', 'url = https://example.com/repo.git')
        assert.equal(staged.run('secretlint.mjs', ['--staged', 'tidy.conf'], { env }).status, 0)

        assert.deepEqual(readdirSync(home), [], 'the staged scan left its scratch copy behind')
      } finally {
        rmSync(home, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 })
      }
    })
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
