#!/usr/bin/env node
/**
 * Refuses commits made on `main`, and pushes that would move it.
 *
 *   node scripts/protected-branch.mjs commit
 *   node scripts/protected-branch.mjs push     # reads the refs being pushed on stdin
 *
 * Every change on `main` is supposed to arrive through a pull request (see the `land-pr` skill). A
 * direct push does not get a review and turns a broken build into a broken default branch, because
 * the CI run on `main` is a *push* trigger. A server-side rule should enforce that too, but this
 * fails at the commit - before work is done on the wrong branch - which a server-side rule cannot.
 *
 * The two cases are different questions, and asking only the first is what #63 fixed:
 *
 *   commit - which branch is checked out. That is the accident: forgetting to branch first.
 *   push   - which refs are being written. `git push origin HEAD:main` from a feature branch never
 *            changes what is checked out, so the branch question misses the case that most directly
 *            breaks the default branch.
 *
 * git hands `pre-push` one line per ref on stdin, which is the same whatever the refspec looked
 * like on the command line:
 *
 *   <local ref> <local sha> <remote ref> <remote sha>
 *   HEAD      abc123... refs/heads/main  def456...      # git push origin HEAD:main
 *   (delete)  0000000... refs/heads/main  def456...     # git push origin :main
 *
 * lefthook does not forward that to a job unless the job sets `use_stdin: true`. Without it this
 * reads nothing, finds no protected ref and allows every push, so lefthook.yml is covered by a test
 * of its own (scripts/lefthook-config.test.mjs).
 *
 * Escape hatch, for the case where `main` genuinely is the right place:
 *
 *   ALLOW_MAIN=1 git commit ...
 *   ALLOW_MAIN=1 git push ...
 */
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const PROTECTED = (process.env.PROTECTED_BRANCHES ?? 'main,master').split(',').map((b) => b.trim())

const action = process.argv[2] ?? 'commit'

if (process.env.ALLOW_MAIN === '1' || process.env.ALLOW_MAIN === 'true') {
  process.exit(0)
}

const refuse = (what, detail) => {
  console.error(
    [
      `Refusing to ${action} ${what}.`,
      '',
      detail,
      '',
      '  git switch -c <type>/<issue>-<slug>',
      '',
      `If this really is right, say so explicitly: ALLOW_MAIN=1 git ${action} ...`,
    ].join('\n'),
  )
  process.exit(1)
}

/**
 * The protected branches named by the refs being pushed.
 *
 * Only `refs/heads/<branch>` counts. A tag called `main` is `refs/tags/main` and is nobody's default
 * branch, so matching on the branch name alone would refuse something harmless.
 */
const pushedProtectedBranches = () => {
  let stdin = ''
  try {
    stdin = readFileSync(0, 'utf8')
  } catch {
    // git can invoke the hook with nothing on stdin, and a closed stdin is not a push to refuse.
    return []
  }

  return stdin
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => line.split(/\s+/)[2])
    .filter((remoteRef) => remoteRef?.startsWith('refs/heads/'))
    .map((remoteRef) => remoteRef.slice('refs/heads/'.length))
    .filter((branch) => PROTECTED.includes(branch))
}

if (action === 'push') {
  const pushed = [...new Set(pushedProtectedBranches())]
  if (pushed.length > 0) {
    refuse(
      `to '${pushed.join("', '")}'`,
      [
        `'${pushed[0]}' only moves through a merge, so that CI reviews the change before it becomes`,
        'the default branch. Open a pull request instead.',
      ].join('\n'),
    )
  }
}

const head = spawnSync('git', ['symbolic-ref', '--quiet', '--short', 'HEAD'], { encoding: 'utf8' })
// A detached HEAD has no branch to protect - that is a rebase or a bisect, not a commit on main.
if (head.status !== 0) process.exit(0)

const branch = head.stdout.trim()
if (!PROTECTED.includes(branch)) process.exit(0)

refuse(
  `on '${branch}'`,
  [
    `Work on a branch and open a pull request; '${branch}' only moves through a merge, so that CI`,
    'reviews the change before it becomes the default branch.',
  ].join('\n'),
)
