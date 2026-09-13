#!/usr/bin/env node
/**
 * Refuses commits and pushes made while `main` is checked out.
 *
 *   node scripts/protected-branch.mjs commit|push
 *
 * Every change on `main` is supposed to arrive through a pull request (see the `land-pr` skill), but
 * the repository is on a plan without branch protection, so nothing on the server enforces it. The
 * CI run on `main` is a *push* trigger: a direct push does not get a review and turns a broken build
 * into a broken default branch. Until branch protection is available, this hook is the enforcement.
 *
 * Escape hatch, for the case where `main` genuinely is the right place to commit:
 *
 *   ALLOW_MAIN=1 git commit ...
 */
import { spawnSync } from 'node:child_process'

const PROTECTED = (process.env.PROTECTED_BRANCHES ?? 'main,master').split(',').map((b) => b.trim())

const action = process.argv[2] ?? 'commit'

if (process.env.ALLOW_MAIN === '1' || process.env.ALLOW_MAIN === 'true') {
  process.exit(0)
}

const head = spawnSync('git', ['symbolic-ref', '--quiet', '--short', 'HEAD'], { encoding: 'utf8' })
// A detached HEAD has no branch to protect - that is a rebase or a bisect, not a commit on main.
if (head.status !== 0) process.exit(0)

const branch = head.stdout.trim()
if (!PROTECTED.includes(branch)) process.exit(0)

console.error(
  [
    `Refusing to ${action} on '${branch}'.`,
    '',
    `Work on a branch and open a pull request; '${branch}' only moves through a merge, so that CI`,
    'reviews the change before it becomes the default branch.',
    '',
    '  git switch -c <type>/<issue>-<slug>',
    '',
    `If committing on '${branch}' really is right, say so explicitly: ALLOW_MAIN=1 git ${action} ...`,
  ].join('\n'),
)
process.exitCode = 1
