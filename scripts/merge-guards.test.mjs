import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { CONFLICT, createRepo, scriptPath } from './test-support.mjs'

/**
 * Git does not run `pre-commit` for a merge commit; it runs `pre-merge-commit`. Until #61 there was
 * no such hook, so a merge introduced content no guard had ever seen - and everything ADR-0007 calls
 * unrecoverable (a credential, an oversized file, a conflict marker) could enter the history that
 * way.
 *
 * These install real hooks and perform real merges, because the half that matters here is not what
 * the guards decide but whether git asks them at all.
 */
// Forward slashes and a single-quoted YAML scalar: a Windows path written with backslashes loses
// them twice over, once to YAML's escape processing and once to the shell lefthook runs the job in.
// Node accepts forward slashes on Windows, and the inner double quotes survive a path with spaces.
const guard = (name, args = '') =>
  `'node "${scriptPath(name).split(path.sep).join('/')}" ${args}{staged_files}'`

const configFor = hook =>
  [
    `${hook}:`,
    '  parallel: true',
    '  jobs:',
    '    - name: hygiene',
    `      run: ${guard('hygiene.mjs')}`,
    '    - name: secrets',
    `      run: ${guard('secretlint.mjs', '--staged ')}`,
    '',
  ].join('\n')

const basicAuthUrl = () => 'url = https://user:' + 'sup3r-s3cret-value' + '@example.com/repo.git'

const conflictedFile = () =>
  [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')

describe('merge commits', () => {
  let repo

  /** Commits `contents` at `file` on a new branch off main, and returns to main. */
  const onBranch = (branch, file, contents) => {
    repo.git('switch', '--quiet', '--create', branch, 'main')
    repo.stage(file, contents)
    repo.git('commit', '--quiet', '--no-verify', '-m', `add ${file} (#61)`)
    repo.git('switch', '--quiet', 'main')
  }

  /** Moves main on, so merging is a real merge commit rather than a fast-forward. */
  const divergeMain = marker => {
    repo.stage(`main-${marker}.txt`, 'main moved on')
    repo.git('commit', '--quiet', '--no-verify', '-m', `main moves on ${marker} (#61)`)
  }

  before(() => {
    repo = createRepo()
    repo.stage('base.txt', 'base')
    repo.git('commit', '--quiet', '-m', 'base (#61)')
    repo.git('branch', '--move', 'main')
    repo.installHooks(configFor('pre-merge-commit'))
  })
  after(() => repo.cleanup())

  test('refuses a merge that brings in a file with a conflict marker', () => {
    onBranch('marker-branch', 'marked.txt', conflictedFile())
    divergeMain('a')

    const { status, stdout, stderr } = repo.tryGit('merge', 'marker-branch', '-m', 'merge (#61)')

    repo.tryGit('merge', '--abort')
    assert.notEqual(status, 0, 'the merge should have been refused')
    assert.match(stdout + stderr, /unresolved merge conflict markers/)
  })

  test('refuses a merge that brings in a file with a credential', () => {
    onBranch('secret-branch', 'leaky.conf', basicAuthUrl())
    divergeMain('b')

    const { status, stdout, stderr } = repo.tryGit('merge', 'secret-branch', '-m', 'merge (#61)')

    repo.tryGit('merge', '--abort')
    assert.notEqual(status, 0, 'the merge should have been refused')
    assert.match(stdout + stderr, /BasicAuth|secretlint|found basic auth/i)
  })

  test('allows a merge that brings in nothing objectionable', () => {
    onBranch('clean-branch', 'fine.txt', 'nothing wrong here')
    divergeMain('c')

    const { status } = repo.tryGit('merge', 'clean-branch', '-m', 'merge (#61)')

    assert.equal(status, 0)
  })

  /**
   * The guards have to see what the *merge* is about to commit, not what some earlier commit
   * touched. `{staged_files}` is what a merge stages, verified here rather than assumed (#61).
   */
  test('checks the files the merge is about to commit', () => {
    onBranch('late-marker', 'late.txt', conflictedFile())
    divergeMain('d')

    const { stdout, stderr } = repo.tryGit('merge', 'late-marker', '-m', 'merge (#61)')
    repo.tryGit('merge', '--abort')

    assert.match(stdout + stderr, /late\.txt/)
  })
})

/**
 * A conflicted merge is not the gap. Git stops, the conflict is resolved by hand and committed
 * separately - and that separate commit runs `pre-commit`, which was covered all along. Pinned
 * because #61 assumed otherwise, and because the two paths are easy to conflate.
 */
describe('a conflicted merge, resolved by hand', () => {
  let repo

  before(() => {
    repo = createRepo()
    repo.stage('shared.txt', 'base')
    repo.git('commit', '--quiet', '-m', 'base (#61)')
    repo.git('branch', '--move', 'main')
    repo.installHooks(configFor('pre-commit'))
  })
  after(() => repo.cleanup())

  test('is caught by pre-commit when the resolution leaves a marker behind', () => {
    repo.git('switch', '--quiet', '--create', 'theirs', 'main')
    repo.stage('shared.txt', 'their version')
    repo.git('commit', '--quiet', '--no-verify', '-m', 'theirs (#61)')
    repo.git('switch', '--quiet', 'main')
    repo.stage('shared.txt', 'our version')
    repo.git('commit', '--quiet', '--no-verify', '-m', 'ours (#61)')

    // The merge stops on the conflict; git creates no commit, so no hook runs yet.
    assert.notEqual(repo.tryGit('merge', 'theirs', '-m', 'merge (#61)').status, 0)

    // Resolve it badly, the way a hurried hand-resolution does.
    repo.stage('shared.txt', conflictedFile())
    const { status, stdout, stderr } = repo.tryGit('commit', '-m', 'resolve the merge (#61)')
    repo.tryGit('merge', '--abort')

    assert.notEqual(status, 0, 'pre-commit should have refused the resolution')
    assert.match(stdout + stderr, /unresolved merge conflict markers/)
  })
})
