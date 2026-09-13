/**
 * A throwaway git repository for the guard tests.
 *
 *   const repo = createRepo()
 *   repo.stage('a.txt', 'contents')
 *   const { status, stderr } = repo.run('hygiene.mjs', ['a.txt'])
 *   repo.cleanup()
 *
 * The guards read the index, the checked-out branch and the tracked file list of whatever directory
 * they are run in, so testing them means giving them a real repository rather than a mock. Every one
 * lives under the OS temp directory: these tests must never write to the working repository, and a
 * repository created by `git init` has no remotes, so `origin` cannot be reached even by mistake.
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath, URL } from 'node:url'

/** The real scripts directory. Guards are always spawned from here, never from a copy. */
export const scriptPath = name => fileURLToPath(new URL(`./${name}`, import.meta.url))

/**
 * Environment for a spawned guard, with the escape hatches cleared.
 *
 * A developer who has exported ALLOW_MAIN or HYGIENE_MAX_BYTES in their shell would otherwise get a
 * green run of tests that assert a refusal - the failure mode these tests exist to catch.
 */
const cleanEnv = overrides => {
  const env = { ...process.env, ...overrides }
  for (const name of ['ALLOW_MAIN', 'HYGIENE_MAX_BYTES', 'PROTECTED_BRANCHES']) {
    if (!(name in overrides)) delete env[name]
  }
  return env
}

export const createRepo = () => {
  // realpath because macOS hands out /var/... symlinks and Windows short paths, either of which
  // makes git report a directory different from the one the test wrote to.
  const dir = mkdtempSync(path.join(realpathSync(tmpdir()), 'skg-guards-'))

  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: dir, encoding: 'utf8' })
    if (result.status !== 0) {
      throw new Error(`git ${args.join(' ')} failed in the test repository:\n${result.stderr}`)
    }
    return result.stdout
  }

  git('init', '--quiet', '--initial-branch=work')
  git('config', 'user.email', 'guards@example.invalid')
  git('config', 'user.name', 'Guard Tests')
  git('config', 'commit.gpgsign', 'false')
  // The working repository installs lefthook into .git/hooks. A throwaway repository must not run
  // anybody's hooks, so point it at a directory that holds none.
  mkdirSync(path.join(dir, '.no-hooks'))
  git('config', 'core.hooksPath', '.no-hooks')

  const write = (file, contents) => {
    const full = path.join(dir, file)
    mkdirSync(path.dirname(full), { recursive: true })
    writeFileSync(full, contents)
    return full
  }

  /** Writes a file and stages it, which is the usual starting point for a guard test. */
  const stage = (file, contents) => {
    write(file, contents)
    git('add', '--', file)
  }

  /** Spawns a guard from the real scripts directory, against this repository. */
  const run = (script, args = [], { env = {}, input } = {}) => {
    const result = spawnSync(process.execPath, [scriptPath(script), ...args], {
      cwd: dir,
      encoding: 'utf8',
      env: cleanEnv(env),
      input,
    })
    return { status: result.status, stdout: result.stdout ?? '', stderr: result.stderr ?? '' }
  }

  const commit = message => git('commit', '--quiet', '--allow-empty', '-m', message)

  const cleanup = () => {
    // Windows keeps a handle on pack files for a moment after git exits; retries beat a flaky test.
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 })
  }

  return { dir, git, write, stage, commit, run, cleanup }
}

/**
 * Strings that must not appear literally in a test file.
 *
 * `hygiene` refuses any file holding conflict markers, and these tests are themselves committed to
 * the repository being guarded. Writing the markers as literals would make the suite unable to be
 * committed - so they are built at run time instead.
 */
export const CONFLICT = {
  open: '<'.repeat(7) + ' HEAD',
  divider: '='.repeat(7),
  close: '>'.repeat(7) + ' branch',
}
