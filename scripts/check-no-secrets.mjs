#!/usr/bin/env node
/**
 * Refuses a committed deployment configuration file that holds a credential rather than a
 * placeholder for one (#48, D8: secrets come from the platform's secret store).
 *
 *   node scripts/check-no-secrets.mjs <paths...>
 *   node scripts/check-no-secrets.mjs --stdin-paths   # newline-separated, for the whole repository
 *
 * secretlint already recognises the shapes of real tokens, anywhere. This covers what it cannot:
 * configuration, where a credential is a value under a key that says what it is (`password:`,
 * `CLIENT_SECRET=`), and the only acceptable value is a reference - `${NEO4J_PASSWORD}`,
 * `${{ secrets.FLY_API_TOKEN }}`, `"$TOKEN"`. So it reads only the files that configure a
 * deployment: the application's own resources, the platform files and the workflows.
 *
 * Content is read from the index, like `hygiene`, so what is judged is what is about to be committed.
 *
 * A line that is deliberately a literal (a documented fixture) says so with `not-a-secret` in a
 * comment on the same line. That is visible in review, which a blanket bypass is not.
 */
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { indexEntries } from './git-index.mjs'

// What configures a deployment. Test resources are not here: their fake credentials are the point.
const SCOPE = [/^backend\/src\/main\/resources\//, /^fly\//, /^ops\//, /^\.github\//]

// The last segment of a key that names a credential: `password`, `client-secret`, `FLY_API_TOKEN`.
const CREDENTIAL_KEY = /(password|passwd|secret|token|api[-_]?key|credentials?|private[-_]?key)$/i

// A key, then `:` or `=`, then the value. Covers YAML, TOML, properties and shell assignments.
const ASSIGNMENT = /^\s*(?:-\s*)?(?:export\s+)?["']?([A-Za-z0-9_.-]+)["']?\s*[:=]\s*(.*)$/

// Shorter literals are settings (`id-token: write`, `token: none`), not credentials.
const MIN_LITERAL = 8

const KNOWN_PREFIXES = [
  { name: 'GitHub token', pattern: /\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})/ },
  { name: 'fly.io token', pattern: /\b(FlyV1\s+fm\d_[A-Za-z0-9_+/=-]{20,}|fo1_[A-Za-z0-9_-]{20,})/ },
  { name: 'AWS access key', pattern: /\b(AKIA|ASIA)[0-9A-Z]{16}\b/ },
  { name: 'Slack token', pattern: /\bxox[abposr]-[A-Za-z0-9-]{10,}/ },
]

const PRIVATE_KEY = /-----BEGIN [A-Z ]*PRIVATE KEY-----/

// Forty or more characters from the base64 alphabet, without `/` so that paths are not runs.
const LONG_RUN = /[A-Za-z0-9+=_-]{40,}/g

/** A value that points at a secret store instead of holding the secret. */
const isReference = (value) =>
  value === '' || value.startsWith('${') || value.startsWith('$') || /^secrets\./.test(value)

/** The value as written, without its quotes and without a trailing comment. */
const bareValue = (raw) => {
  const trimmed = raw.trim()
  const quoted = /^(["'])(.*?)\1/.exec(trimmed)
  if (quoted) return quoted[2]
  return trimmed.replace(/\s+#.*$/, '').trim()
}

/** Long runs that carry upper case, lower case and a digit: an encoded secret rather than a word or a hash. */
const encodedRun = (line) =>
  (line.match(LONG_RUN) ?? []).find(
    (run) => /[A-Z]/.test(run) && /[a-z]/.test(run) && /[0-9]/.test(run) && !/^[0-9a-fA-F]+$/.test(run),
  )

const findings = (line) => {
  const found = []
  if (PRIVATE_KEY.test(line)) found.push('a private key block')

  const known = KNOWN_PREFIXES.find(({ pattern }) => pattern.test(line))
  if (known) found.push(`a ${known.name}`)

  const assignment = ASSIGNMENT.exec(line)
  if (assignment && CREDENTIAL_KEY.test(assignment[1])) {
    const value = bareValue(assignment[2])
    if (!isReference(value) && value.length >= MIN_LITERAL && !/\s/.test(value)) {
      found.push(`a literal value for "${assignment[1]}" where a \${...} placeholder belongs`)
    }
  }

  // References are stripped first, so a long secret name inside `${{ secrets.X }}` is not a run.
  if (found.length === 0 && encodedRun(line.replace(/\$\{\{[^}]*\}\}|\$\{[^}]*\}/g, ''))) {
    found.push('a long encoded value')
  }
  return found
}

const args = process.argv.slice(2)
const paths = (args.includes('--stdin-paths') ? readFileSync(0, 'utf8').split(/\r?\n/) : args)
  .filter(Boolean)
  .map((file) => file.split(path.sep).join('/'))
  .filter((file) => SCOPE.some((scope) => scope.test(file)))

const problems = []

for (const { file, content } of indexEntries(paths)) {
  if (content.includes(0)) continue
  content
    .toString('utf8')
    .split(/\r?\n/)
    .forEach((line, index) => {
      if (/not-a-secret/.test(line)) return
      for (const finding of findings(line)) problems.push(`${file}:${index + 1}: ${finding}`)
    })
}

if (problems.length > 0) {
  console.error('Deployment configuration holds what looks like a credential:\n')
  for (const problem of problems) console.error(`  ${problem}`)
  console.error(
    '\nReference the platform secret store instead (${ENV}, ${{ secrets.NAME }}, "$NAME"). If the',
    'value really is not a credential, say so with a "not-a-secret" comment on the same line.\n',
  )
  process.exitCode = 1
}
