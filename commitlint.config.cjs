/**
 * Conventional commits with a mandatory issue reference, e.g.
 *   feat(backend): add ontology registry endpoint (#18)
 *   docs: add roadmap (#16)
 *
 * The scope is optional but, when given, must be one of the listed areas.
 */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'references-empty': [2, 'never'],
    'scope-enum': [
      2,
      'always',
      ['backend', 'frontend', 'ontology', 'connector', 'ci', 'docs', 'e2e', 'policy', 'deps'],
    ],
  },
}
