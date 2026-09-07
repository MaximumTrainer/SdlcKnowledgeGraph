#!/usr/bin/env node
// Lints GitHub Actions workflows with actionlint.
//
// The `actionlint` npm package ships the linter as WebAssembly with a Node API and no executable,
// so `npx actionlint <files>` cannot run it: npx exits with "could not determine executable to run"
// and the hook fails on every commit that touches a workflow. This wrapper calls the API directly.
//
// Usage: node scripts/actionlint.mjs .github/workflows/ci.yml [...]
import { readFile } from 'node:fs/promises'
import { sep } from 'node:path'
import { createLinter } from 'actionlint'

const files = process.argv.slice(2)
if (files.length === 0) process.exit(0)

const lint = await createLinter()

/**
 * Findings for one file.
 *
 * The wasm binding reports them by throwing an Error whose message is the JSON array, rather than
 * returning them, so a caller that only reads the return value passes silently. The path is
 * normalised to forward slashes first: it is echoed back inside that JSON unescaped, and a Windows
 * path makes the message unparseable.
 */
const problemsIn = (source, file) => {
  try {
    return lint(source, file.split(sep).join('/')) ?? []
  } catch (error) {
    try {
      return JSON.parse(error.message)
    } catch {
      return [{ line: 0, column: 0, kind: 'actionlint', message: error.message }]
    }
  }
}

let failed = false

for (const file of files) {
  for (const problem of problemsIn(await readFile(file, 'utf8'), file)) {
    failed = true
    console.error(`${file}:${problem.line}:${problem.column}: ${problem.message} [${problem.kind}]`)
  }
}

// Exit through the code rather than process.exit(): the Go wasm runtime still holds handles, and
// tearing it down mid-flight trips a libuv assertion on Windows that replaces the status with 127.
process.exitCode = failed ? 1 : 0
