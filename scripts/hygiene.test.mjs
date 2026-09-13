import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { CONFLICT, createRepo } from './test-support.mjs'

/**
 * `hygiene` decides whether a commit is allowed. Its failure mode is silent: a guard that has
 * stopped refusing looks exactly like a guard with nothing to refuse, because commits keep
 * succeeding either way. These pin both halves - what must be refused, and the false positives that
 * must not fire, because a guard that cries wolf is one people learn to bypass with LEFTHOOK=0.
 */
describe('hygiene', () => {
  let repo

  before(() => {
    repo = createRepo()
  })
  after(() => repo.cleanup())

  const hygiene = (paths, env) => repo.run('hygiene.mjs', paths, { env })

  describe('conflict markers', () => {
    test('refuses a file with an opener and a closer', () => {
      repo.stage(
        'conflicted.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )

      const { status, stderr } = hygiene(['conflicted.txt'])

      assert.equal(status, 1)
      assert.match(stderr, /unresolved merge conflict markers/)
    })

    test('allows ======= on its own, which is a Markdown setext heading', () => {
      repo.stage('heading.md', ['A title', CONFLICT.divider, '', 'Body text.'].join('\n'))

      assert.equal(hygiene(['heading.md']).status, 0)
    })

    test('allows an opener without a closer, which is a diff being discussed', () => {
      repo.stage('prose.md', ['Explaining a conflict:', '', CONFLICT.open, 'ours'].join('\n'))

      assert.equal(hygiene(['prose.md']).status, 0)
    })
  })

  describe('file size', () => {
    test('refuses a file over the limit', () => {
      repo.stage('big.bin', 'x'.repeat(4096))

      const { status, stderr } = hygiene(['big.bin'], { HYGIENE_MAX_BYTES: '1024' })

      assert.equal(status, 1)
      assert.match(stderr, /exceeds the 1 KiB limit/)
    })

    test('respects HYGIENE_MAX_BYTES when it is raised deliberately', () => {
      repo.stage('big.bin', 'x'.repeat(4096))

      assert.equal(hygiene(['big.bin'], { HYGIENE_MAX_BYTES: '8192' }).status, 0)
    })
  })

  describe('credential files', () => {
    test('refuses a path that exists to hold a secret', () => {
      repo.stage('.env', 'TOKEN=value\n')

      const { status, stderr } = hygiene(['.env'])

      assert.equal(status, 1)
      assert.match(stderr, /looks like a credential file/)
    })

    test('refuses a key anywhere in the tree, not only at the root', () => {
      repo.stage('deploy/keys/server.pem', 'not actually a key\n')

      assert.equal(hygiene(['deploy/keys/server.pem']).status, 1)
    })

    test('allows .env.example, which is how a .env is documented', () => {
      repo.stage('.env.example', 'TOKEN=<your token>\n')

      assert.equal(hygiene(['.env.example']).status, 0)
    })
  })

  describe('reading the index', () => {
    test('judges staged content, not what the working tree now holds', () => {
      repo.stage(
        'staged-bad.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )
      // Tidy the working copy after staging. What gets committed is still the conflicted version.
      repo.write('staged-bad.txt', 'resolved\n')

      assert.equal(hygiene(['staged-bad.txt']).status, 1)
    })

    test('allows a clean index even when the working tree is dirty', () => {
      repo.stage('staged-good.txt', 'fine\n')
      repo.write(
        'staged-good.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )

      assert.equal(hygiene(['staged-good.txt']).status, 0)
    })

    test('skips a path the index cannot resolve, such as one never staged', () => {
      repo.write('untracked.txt', [CONFLICT.open, CONFLICT.close].join('\n'))

      assert.equal(hygiene(['untracked.txt']).status, 0)
    })
  })

  describe('binary content', () => {
    test('skips a file with a NUL byte, which has no line structure to scan', () => {
      repo.stage(
        'blob.bin',
        Buffer.concat([Buffer.from([0, 1, 2]), Buffer.from([CONFLICT.open, CONFLICT.close].join('\n'))])
      )

      assert.equal(hygiene(['blob.bin']).status, 0)
    })

    /**
     * hygiene reads every staged path through one `git cat-file --batch`, walking the stream by the
     * byte count in each header. Miscount one entry and every later file is parsed from the wrong
     * offset - which shows up as guards that silently stop refusing, not as an error. A binary blob
     * is where that goes wrong, so a text file with markers is placed after one.
     */
    test('stays aligned in the batch stream after a binary blob', () => {
      repo.stage('first.bin', Buffer.from([0, 255, 0, 255, 0]))
      repo.stage(
        'second.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )

      const { status, stderr } = hygiene(['first.bin', 'second.txt'])

      assert.equal(status, 1)
      assert.match(stderr, /second\.txt: unresolved merge conflict markers/)
    })

    test('stays aligned when a missing path sits between two staged ones', () => {
      repo.stage('before.txt', 'fine\n')
      repo.stage(
        'after.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )

      const { status, stderr } = hygiene(['before.txt', 'never-staged.txt', 'after.txt'])

      assert.equal(status, 1)
      assert.match(stderr, /after\.txt: unresolved merge conflict markers/)
    })
  })

  describe('input', () => {
    test('reads a path list from stdin, for lists too long for a command line', () => {
      repo.stage(
        'from-stdin.txt',
        [CONFLICT.open, 'ours', CONFLICT.divider, 'theirs', CONFLICT.close].join('\n')
      )

      const result = repo.run('hygiene.mjs', ['--stdin-paths'], { input: 'from-stdin.txt\n' })

      assert.equal(result.status, 1)
      assert.match(result.stderr, /from-stdin\.txt/)
    })

    test('passes when given nothing to check', () => {
      assert.equal(hygiene([]).status, 0)
    })
  })
})
