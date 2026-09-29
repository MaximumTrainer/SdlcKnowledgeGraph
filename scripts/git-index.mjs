/**
 * Reading staged content, shared by the guards that judge a commit by what it will contain.
 */
import { spawnSync } from 'node:child_process'

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
export const indexEntries = (files) => {
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
