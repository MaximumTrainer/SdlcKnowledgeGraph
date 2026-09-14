/**
 * Conventional commits with a mandatory issue reference, e.g.
 *   feat(backend): add ontology registry endpoint (#18)
 *   docs: add roadmap (#16)
 *
 * The scope is optional but, when given, must be one of the listed areas.
 *
 * The issue reference is what makes the history answer "why was this done" without a code
 * archaeology session, so it is required of everything a person writes.
 */

/**
 * The trailer Dependabot signs every commit with.
 *
 * Dependabot writes its own commit messages and has no issue to reference, so `references-empty`
 * would fail every update it opens and none of them could be merged - rebuilding the dependency
 * backlog that #68 existed to clear, with each item now also showing a red check (#119).
 *
 * Matched on the signature rather than on the subject line deliberately. A human commit that merely
 * looks like a dependency bump - `chore(deps): Bump vite from 7.3.6 to 7.4.0 in /frontend` - is
 * still held to the rule, because anyone can write that subject by hand and only Dependabot can
 * sign as Dependabot. A subject-shaped exemption would be a blanket opt-out with extra steps.
 */
const DEPENDABOT_SIGNOFF = /^Signed-off-by: dependabot\[bot\] <support@github\.com>$/m

module.exports = {
  extends: ['@commitlint/config-conventional'],
  // `ignores` skips a message entirely rather than relaxing one rule for it. That is the whole
  // behaviour commitlint offers here, and it is acceptable for these: Dependabot's subjects are
  // already conventional and its scope is already one of the declared ones, which the tests in
  // scripts/commitlint-config.test.mjs pin.
  ignores: [(message) => DEPENDABOT_SIGNOFF.test(message)],
  rules: {
    'references-empty': [2, 'never'],
    'scope-enum': [
      2,
      'always',
      ['backend', 'frontend', 'ontology', 'connector', 'ci', 'docs', 'e2e', 'policy', 'deps'],
    ],
  },
}
