import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath, URL } from 'node:url'
import { resolveBin } from './npm-bin.mjs'

/**
 * The commit message gate, exercised through the real CLI against the real config.
 *
 * `references-empty` is what makes the history traceable: every commit names the issue it belongs
 * to. Dependabot writes its own commits and has no issue to name, so without an exemption every
 * update it opens fails the check and cannot merge - which rebuilds the backlog #68 existed to
 * clear (#119). These pin the exemption *and* its edges, because a rule relaxed too far is the same
 * as no rule.
 */
const repoRoot = fileURLToPath(new URL('..', import.meta.url))

/** Runs commitlint over a message exactly as the hook and the CI job do. */
const lint = message => {
  const result = spawnSync(process.execPath, [resolveBin('@commitlint/cli', 'commitlint')], {
    cwd: repoRoot,
    input: message,
    encoding: 'utf8',
  })
  return { status: result.status, output: (result.stdout ?? '') + (result.stderr ?? '') }
}

/** The trailer Dependabot signs every commit with. */
const DEPENDABOT_SIGNOFF = 'Signed-off-by: dependabot[bot] <support@github.com>'

describe('commitlint', () => {
  test('accepts a conventional commit that references an issue', () => {
    assert.equal(lint('fix(ci): refuse a push that would write a protected branch (#63)').status, 0)
  })

  test('rejects a commit with no issue reference', () => {
    const { status, output } = lint('fix(ci): something without a reference')

    assert.notEqual(status, 0)
    assert.match(output, /references-empty/)
  })

  test('rejects an unknown scope', () => {
    assert.notEqual(lint('fix(nonsense): a scope that is not declared (#1)').status, 0)
  })

  describe('Dependabot', () => {
    /** A message shaped exactly as Dependabot writes one, trailer included. */
    const dependabot = subject =>
      [
        subject,
        '',
        'Bumps something from 1.0.0 to 2.0.0.',
        '',
        'updated-dependencies:',
        '- dependency-name: something',
        '  dependency-type: direct:development',
        '',
        DEPENDABOT_SIGNOFF,
      ].join('\n')

    test('accepts an update commit that cannot reference an issue', () => {
      assert.equal(lint(dependabot('chore(deps): Bump vite from 7.3.6 to 7.4.0 in /frontend')).status, 0)
    })

    test('accepts the grouped action updates, which use the ci type', () => {
      assert.equal(lint(dependabot('ci: Bump the actions group with 9 updates')).status, 0)
    })

    /**
     * The exemption keys on the signature, not the subject. A person writing a commit that merely
     * looks like a dependency bump is still held to the rule - otherwise this is a blanket opt-out
     * with extra steps.
     */
    test('does not exempt a human commit that merely looks like an update', () => {
      const { status, output } = lint('chore(deps): Bump vite from 7.3.6 to 7.4.0 in /frontend')

      assert.notEqual(status, 0)
      assert.match(output, /references-empty/)
    })

    test('does not exempt a human commit that names dependabot in its body', () => {
      const message = ['chore(deps): bump something', '', 'Following up on dependabot[bot].'].join('\n')

      assert.notEqual(lint(message).status, 0)
    })
  })
})
