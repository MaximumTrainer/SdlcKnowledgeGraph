#!/usr/bin/env node
/**
 * Repository hygiene guard for the `pre-commit` hook and the CI parity job.
 *
 *   node scripts/hygiene.mjs <paths...>
 *   node scripts/hygiene.mjs --stdin-paths   # newline-separated paths, for lists too long for argv
 *
 * Three things a formatter and a linter cannot catch, because they are about what is being committed
 * rather than how it is written (see docs/adr/0007-commit-guards.md):
 *
 *   1. Unresolved merge conflict markers. They compile in neither language but do commit cleanly,
 *      and once pushed they are someone else's problem.
 *   2. Oversized files. A 40 MB jar or a stray build output in the history cannot be removed without
 *      rewriting it, so the only cheap moment to refuse one is before the commit.
 *   3. Credential files. `.env`, private keys and keystores are never meant to be tracked, and the
 *      content scanner cannot help once the file is an opaque binary like a PKCS#12 store.
 *
 * Content is read from the index, not the working tree, so what is checked is exactly what is about
 * to be committed - partially staged files included.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'

const MAX_BYTES = Number(process.env.HYGIENE_MAX_BYTES ?? 512 * 1024)

// Files that exist to hold a secret. Matched against the path, case-insensitively.
const CREDENTIAL_PATTERNS = [
  /(^|\/)\.env(\.|$)/i,
  /(^|\/)id_(rsa|dsa|ecdsa|ed25519)$/i,
  /\.(pem|key|pfx|p12|jks|keystore|ppk)$/i,
  /(^|\/)\.npmrc$/i,
  /(^|\/)(credentials|service-account.*\.json)$/i,
]

// `.env.example` and friends are templates with placeholder values, which is how a real `.env` is
// documented. They are the exception the rule above would otherwise swallow.
const CREDENTIAL_ALLOW = [/\.(example|sample|template|dist)$/i]

// Paths the credential rule must not refuse, listed in `.hygieneignore` beside `.secretlintignore`.
//
// The rule matches on the path, and plenty of credential-shaped paths hold no credential: an
// `.npmrc` setting `engine-strict`, a public certificate, a test fixture keystore. Without a way
// out, the first false positive is met with LEFTHOOK=0 - which turns off the secret scanner, the
// conflict-marker check and the branch guard as well (ADR-0007, #64).
//
// This forgives the *name* only. The listed file is still checked for size and conflict markers,
// and `secrets` still scans its content - otherwise the allowlist would be the way to smuggle a
// credential past the gate.
const HYGIENE_IGNORE = '.hygieneignore'

/**
 * A line of `.hygieneignore` as an anchored matcher.
 *
 * Anchored at both ends deliberately: an entry has to match the whole repository-relative path. A
 * matcher that accepted a bare filename anywhere in the tree would forgive `deploy/.npmrc` because
 * somebody listed the harmless one at the root, which is exactly the file this guard exists for.
 *
 * Written by hand rather than with a glob library: the hook runs on every commit, and this is the
 * only globbing it needs. `*` stops at a separator, `**` does not, `?` is one character.
 */
const globToRegExp = (pattern) => {
  let source = '^'
  for (let i = 0; i < pattern.length; i += 1) {
    const char = pattern[i]
    if (char === '*' && pattern[i + 1] === '*') {
      // `**/` spans any number of directories, including none; a trailing `**` spans the rest.
      const slashed = pattern[i + 2] === '/'
      source += slashed ? '(?:[^/]+/)*' : '.*'
      i += slashed ? 2 : 1
    } else if (char === '*') {
      source += '[^/]*'
    } else if (char === '?') {
      source += '[^/]'
    } else {
      source += char.replace(/[.+^${}()|[\]\\]/, '\\$&')
    }
  }
  return new RegExp(source + '$')
}

const allowlist = () => {
  if (!existsSync(HYGIENE_IGNORE)) return []
  return readFileSync(HYGIENE_IGNORE, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.replace(/#.*$/, '').trim())
    .filter(Boolean)
    .map(globToRegExp)
}

const ALLOWED_CREDENTIAL_PATHS = allowlist()

const args = process.argv.slice(2)
// cmd.exe caps a command line at 8191 characters, which a whole-repository run exceeds. Reading the
// list from stdin keeps `npm run guards` working on Windows.
const paths = (
  args.includes('--stdin-paths') ? readFileSync(0, 'utf8').split(/\r?\n/) : args
)
  .filter(Boolean)
  // lefthook hands over native paths; git only speaks forward slashes.
  .map((file) => file.split(path.sep).join('/'))

const problems = []
const report = (file, message) => problems.push(`${file}: ${message}`)

/**
 * Index entries for the given paths, as `{ file, size, content }`.
 *
 * One `git cat-file --batch` for the whole list rather than two processes per file: on Windows a
 * process launch costs more than every check in this script put together, and this hook runs on
 * every commit.
 *
 * A path git cannot resolve in the index is reported back as `<request> missing` and skipped. That
 * is a file being deleted, or one passed by hand that was never staged - neither is a failure here.
 */
const indexEntries = (files) => {
  if (files.length === 0) return []

  const batch = spawnSync('git', ['cat-file', '--batch'], {
    input: files.map((file) => `:${file}`).join('\n') + '\n',
    maxBuffer: 512 * 1024 * 1024,
  })
  if (batch.status !== 0) {
    console.error(`git cat-file failed: ${batch.stderr?.toString().trim()}`)
    process.exit(1)
  }

  const out = batch.stdout
  const entries = []
  let offset = 0

  for (const file of files) {
    const newline = out.indexOf(0x0a, offset)
    if (newline === -1) break

    const header = out.toString('utf8', offset, newline)
    offset = newline + 1
    if (header.endsWith(' missing')) continue

    // `<oid> <type> <size>`, then exactly <size> bytes, then a newline.
    const size = Number(header.slice(header.lastIndexOf(' ') + 1))
    entries.push({ file, size, content: out.subarray(offset, offset + size) })
    offset += size + 1
  }

  return entries
}

for (const file of paths) {
  const looksLikeCredential =
    CREDENTIAL_PATTERNS.some((p) => p.test(file)) && !CREDENTIAL_ALLOW.some((p) => p.test(file))
  if (looksLikeCredential && !ALLOWED_CREDENTIAL_PATHS.some((p) => p.test(file))) {
    report(
      file,
      'looks like a credential file and must not be tracked. ' +
        `Add it to ${HYGIENE_IGNORE} if it holds no credential - that forgives the name only, ` +
        'and leaves the secret scanner reading the content.',
    )
  }
}

for (const { file, size, content } of indexEntries(paths)) {
  if (size > MAX_BYTES) {
    report(
      file,
      `${(size / 1024).toFixed(0)} KiB exceeds the ${(MAX_BYTES / 1024).toFixed(0)} KiB limit. ` +
        'Keep large artefacts out of the history, or raise HYGIENE_MAX_BYTES deliberately.',
    )
  }

  // A NUL byte means binary: there is no line structure to scan for markers. Note the blind
  // spot this leaves - a *source* file that happens to contain a raw NUL reads as binary to
  // this guard exactly as it does to git, so it is stepped over rather than checked. Writing
  // the character as an escape keeps a source file text on both counts (#66).
  if (content.includes(0)) continue

  const lines = content.toString('utf8').split(/\r?\n/)
  // Both an opener and a closer are required. `=======` alone is a setext heading in Markdown, and a
  // rule that flagged it would fire on half the documentation in this repository.
  const opens = lines.findIndex((line) => /^<{7}(\s|$)/.test(line))
  const closes = lines.findIndex((line) => /^>{7}(\s|$)/.test(line))
  if (opens !== -1 && closes !== -1) {
    report(file, `unresolved merge conflict markers at lines ${opens + 1} and ${closes + 1}.`)
  }
}

if (problems.length > 0) {
  console.error('Repository hygiene check failed:\n')
  for (const problem of problems) console.error(`  ${problem}`)
  console.error('')
  process.exitCode = 1
}
