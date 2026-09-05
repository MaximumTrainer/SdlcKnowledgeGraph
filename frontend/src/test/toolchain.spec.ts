import { describe, expect, it } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * The lint and format toolchain is a gate: the pre-commit hook and CI both depend on these scripts
 * and config files existing under exactly these names. Deleting one degrades the gate silently,
 * because a hook job whose command is missing is easy to miss in the noise of a commit. These tests
 * pin the contract described in issue #12.
 */
const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const read = (relativePath: string) => readFileSync(resolve(repoRoot, relativePath), 'utf8')
const readJson = (relativePath: string) => JSON.parse(read(relativePath))

describe('frontend lint and format toolchain', () => {
  const pkg = readJson('frontend/package.json')

  it.each([
    ['lint', 'eslint .'],
    ['lint:fix', 'eslint . --fix'],
    ['format', 'prettier --write .'],
    ['format:check', 'prettier --check .']
  ])('defines the %s script', (name, command) => {
    expect(pkg.scripts[name]).toBe(command)
  })

  it('typechecks with vue-tsc against the app tsconfig', () => {
    expect(pkg.scripts.typecheck).toContain('vue-tsc --noEmit')
  })

  it('formats with the house style', () => {
    expect(readJson('frontend/.prettierrc')).toMatchObject({
      semi: false,
      singleQuote: true,
      printWidth: 100,
      trailingComma: 'none'
    })
  })

  it('excludes build output from linting', () => {
    const config = read('frontend/eslint.config.js')
    for (const ignored of ['dist/**', 'coverage/**', 'node_modules/**']) {
      expect(config).toContain(ignored)
    }
  })
})

describe('end-to-end project lint toolchain', () => {
  it('has its own ESLint config so `eslint .` runs in e2e/', () => {
    expect(existsSync(resolve(repoRoot, 'e2e/eslint.config.js'))).toBe(true)
  })

  it('reuses the frontend config rather than redefining the rules', () => {
    expect(read('e2e/eslint.config.js')).toContain('../frontend/eslint.config.js')
  })

  it('exposes a lint script', () => {
    expect(readJson('e2e/package.json').scripts.lint).toBe('eslint .')
  })
})

describe('workflow and Kotlin linting', () => {
  it('checks GitHub workflow files with actionlint on commit', () => {
    const lefthook = read('lefthook.yml')
    expect(lefthook).toContain('actionlint')
    expect(lefthook).toContain('.github/workflows/')
  })

  it('writes ktlint reports in both plain and checkstyle form', () => {
    const build = read('backend/build.gradle.kts')
    expect(build).toContain('ReporterType.PLAIN')
    expect(build).toContain('ReporterType.CHECKSTYLE')
  })

  it('caps parameter lists and line length in the detekt config', () => {
    const detekt = read('backend/config/detekt/detekt.yml')
    expect(detekt).toMatch(/functionThreshold:\s*8/)
    expect(detekt).toMatch(/maxLineLength:\s*140/)
  })
})
