import { describe, expect, it } from 'vitest'
import { parseGitRemote, InvalidGitRemoteError } from './gitRemote'
import table from '@/test/fixtures/git-remotes.json'

/**
 * The same table the Kotlin parser is tested against, read from the same file.
 *
 * Two implementations of one rule drift the moment they are tested separately, and the editing
 * screen previews the key this parser derives while the API stores the key the Kotlin one derives.
 * A preview that disagrees with what gets saved is worse than no preview at all, so neither side
 * owns the table.
 */
describe('parseGitRemote', () => {
  describe('accepts every form a real remote is written in', () => {
    for (const expected of table.accepted) {
      it(`${expected.input.trim() || '<blank>'} — ${expected.why}`, () => {
        const remote = parseGitRemote(expected.input)

        expect(remote.host).toBe(expected.host)
        expect(remote.org).toBe(expected.org)
        expect(remote.name).toBe(expected.name)
        expect(remote.key).toBe(`${expected.host}/${expected.org}/${expected.name}`)
        expect(remote.canonicalUrl).toBe(
          `https://${expected.host}/${expected.org}/${expected.name}`
        )
      })
    }
  })

  describe('refuses what is not a git remote', () => {
    for (const expected of table.rejected) {
      it(`${expected.input.trim() || '<blank>'} — ${expected.why}`, () => {
        expect(() => parseGitRemote(expected.input)).toThrow(InvalidGitRemoteError)
      })
    }
  })
})
