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
import { copyFileSync, mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath, URL } from 'node:url'

/** The real scripts directory. Guards are always spawned from here, never from a copy. */
export const scriptPath = name => fileURLToPath(new URL(`./${name}`, import.meta.url))

/**
 * The platform lefthook binary, for tests that install real git hooks.
 *
 * Asked of the package rather than hardcoded, because the name carries the platform and the
 * architecture - lefthook-windows-x64, lefthook-linux-arm64 - and these tests run on more than one.
 */
const lefthookExe = () => createRequire(import.meta.url)('lefthook/get-exe').getExePath()

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

  // Git subcommands that create a commit will invoke the installed hooks, and the hook script
  // looks for lefthook under the repository's own node_modules, which a throwaway repository has
  // none of. LEFTHOOK_BIN is the documented way to point it at one.
  const gitEnv = { ...process.env, LEFTHOOK_BIN: lefthookExe() }

  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: dir, encoding: 'utf8', env: gitEnv })
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

  // secretlint refuses to run without a config, exiting 2. That is indistinguishable from a finding
  // to a caller checking only for a non-zero status, so a test repository without one would let
  // every "the secret is refused" assertion pass for the wrong reason. The real config is copied in
  // rather than invented, so the tests exercise the ruleset this repository actually commits to.
  copyFileSync(scriptPath('../.secretlintrc.json'), path.join(dir, '.secretlintrc.json'))
  writeFileSync(path.join(dir, '.secretlintignore'), '')

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

  /**
   * Installs real git hooks from the given lefthook configuration.
   *
   * Jobs should invoke the guards by absolute path, since the throwaway repository has no scripts/
   * directory of its own. What this proves is the half a unit test cannot: that git actually calls
   * the hook for the operation in question, and that the guard's refusal stops it.
   */
  const installHooks = config => {
    writeFileSync(path.join(dir, 'lefthook.yml'), config)
    git('config', '--unset', 'core.hooksPath')
    const installed = spawnSync(lefthookExe(), ['install', '--force'], { cwd: dir, encoding: 'utf8' })
    if (installed.status !== 0) {
      throw new Error(`lefthook install failed in the test repository:
${installed.stderr}`)
    }
  }

  /** Runs a git command that is expected to fail, returning its status and output. */
  const tryGit = (...args) => {
    const result = spawnSync('git', args, { cwd: dir, encoding: 'utf8', env: gitEnv })
    return { status: result.status, stdout: result.stdout ?? '', stderr: result.stderr ?? '' }
  }

  const cleanup = () => {
    // Windows keeps a handle on pack files for a moment after git exits; retries beat a flaky test.
    rmSync(dir, { recursive: true, force: true, maxRetries: 10, retryDelay: 50 })
  }

  return { dir, git, tryGit, write, stage, commit, run, installHooks, cleanup }
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
