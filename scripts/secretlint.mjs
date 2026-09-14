#!/usr/bin/env node
/**
 * Runs secretlint over the given paths.
 *
 *   node scripts/secretlint.mjs <paths...>            # as they are on disk
 *   node scripts/secretlint.mjs --staged <paths...>   # as they are in the index
 *
 * A wrapper rather than a bare CLI call so that `--secretlintignore` lives in one place instead of in
 * both the hook and the CI entry point, where the two could drift apart and scan different trees.
 * See scripts/npm-bin.mjs for why the CLI is not reached through npx.
 *
 * `--staged` exists because the hook and the CLI disagree about what "the file" means. secretlint
 * reads paths from disk; what a commit contains is the index. Stage a file holding a token, tidy the
 * working copy before committing - an ordinary thing to do - and the gate passes while the token
 * goes into the commit. The mirror image is just as damaging: a secret present only in the working
 * tree fails a commit that would not have contained it, which is how people learn to reach for
 * LEFTHOOK=0 (#62, ADR-0007).
 *
 * So the staged content is written to a scratch directory, at the same relative paths, and scanned
 * there. Same relative paths for two reasons: secretlint picks its rules by file extension, and
 * `.secretlintignore` patterns are relative, so a flattened copy would quietly change both.
 *
 * The whole-repository run (`npm run guards`) keeps reading the working tree, where the index and
 * the tree are the same thing and there is nothing to reconcile.
 */
import { spawnSync } from 'node:child_process'
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { resolveBin } from './npm-bin.mjs'

const SECRETLINT_IGNORE = '.secretlintignore'
const SECRETLINT_RC = '.secretlintrc.json'

const args = process.argv.slice(2)
const staged = args[0] === '--staged'
const paths = (staged ? args.slice(1) : args).filter(Boolean)

if (paths.length === 0) process.exit(0)

const bin = resolveBin('secretlint')

const runSecretlint = (targets, cwd, capture) =>
  spawnSync(process.execPath, [bin, '--secretlintignore', SECRETLINT_IGNORE, ...targets], {
    cwd,
    // Inherited for the working-tree run so output streams as it is produced; captured for the
    // staged run, which has to rewrite the scratch paths before anyone sees them.
    stdio: capture ? 'pipe' : 'inherit',
    encoding: capture ? 'utf8' : undefined,
  })

const failed = (result) => {
  console.error(`Failed to run secretlint: ${result.error.message}`)
  return 1
}

if (!staged) {
  const result = runSecretlint(paths, undefined, false)
  process.exit(result.error ? failed(result) : (result.status ?? 1))
}

/**
 * Scans the staged content of the given paths, and returns the exit status.
 *
 * The scratch directory is removed on every path out of here, findings included - it holds a copy of
 * the very credential being refused. `process.exit` is deliberately not called inside: it terminates
 * without unwinding, so a `finally` would never run and the copy would be left on disk.
 */
const scanStaged = () => {
  const scratch = mkdtempSync(path.join(realpathSync(tmpdir()), 'secretlint-staged-'))

  try {
    const present = []
    for (const file of paths) {
      const blob = spawnSync('git', ['show', `:${file}`], { maxBuffer: 512 * 1024 * 1024 })
      // A path the index cannot resolve is one being deleted, or one passed by hand that was never
      // staged. Neither is a failure here - the same rule scripts/hygiene.mjs follows.
      if (blob.status !== 0) continue

      const target = path.join(scratch, file)
      mkdirSync(path.dirname(target), { recursive: true })
      writeFileSync(target, blob.stdout)
      present.push(file)
    }

    if (present.length === 0) return 0

    // Copied rather than pointed at, so the scan inside the scratch directory resolves its rules and
    // ignore patterns exactly as a scan of the repository would.
    copyFileSync(SECRETLINT_RC, path.join(scratch, SECRETLINT_RC))
    const ignore = path.join(scratch, SECRETLINT_IGNORE)
    if (existsSync(SECRETLINT_IGNORE)) copyFileSync(SECRETLINT_IGNORE, ignore)
    else writeFileSync(ignore, '')

    const result = runSecretlint(present, scratch, true)
    if (result.error) return failed(result)

    // Findings name the scratch copy, which is of no use to whoever has to fix one. Strip the
    // prefix so what is reported is the path in this repository.
    //
    // Both spellings, because on Windows they differ: mkdtemp returns `C:\...\secretlint-staged-x`
    // and secretlint prints `C:/.../secretlint-staged-x/nested/leak.conf`.
    const prefixes = [scratch + path.sep, scratch + '/', scratch.split(path.sep).join('/') + '/']
    const repoPaths = (text) =>
      prefixes.reduce((out, prefix) => out.split(prefix).join(''), text ?? '')

    process.stdout.write(repoPaths(result.stdout))
    process.stderr.write(repoPaths(result.stderr))
    return result.status ?? 1
  } finally {
    rmSync(scratch, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 })
  }
}

process.exit(scanStaged())
