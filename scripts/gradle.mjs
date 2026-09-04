#!/usr/bin/env node
/**
 * Cross-platform Gradle wrapper launcher used by lefthook and npm scripts.
 *
 *   node scripts/gradle.mjs <gradle args...>
 *
 * Runs `gradlew.bat` on Windows and `./gradlew` elsewhere, always from the `backend/` directory,
 * passing all arguments through and propagating the exit code.
 */
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const backendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'backend')
const isWindows = process.platform === 'win32'
const wrapper = path.join(backendDir, isWindows ? 'gradlew.bat' : 'gradlew')
const args = process.argv.slice(2)

// Node refuses to spawn .bat/.cmd files without a shell (CVE-2024-27980), so use one on Windows.
// The absolute path is quoted because cmd.exe receives the whole command line as a single string.
const result = spawnSync(isWindows ? `"${wrapper}"` : wrapper, args, {
  cwd: backendDir,
  stdio: 'inherit',
  shell: isWindows,
})

if (result.error) {
  console.error(`Failed to run ${wrapper}: ${result.error.message}`)
  process.exit(1)
}
process.exit(result.status ?? 1)
