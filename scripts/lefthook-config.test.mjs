import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath, URL } from 'node:url'

/**
 * The guards are only as good as the hook configuration that reaches them, and a guard wired up
 * wrongly fails the way guards always fail - silently, by refusing nothing.
 *
 * This reads lefthook.yml as text rather than parsing YAML. A dependency for one assertion is not
 * worth it, and the thing being pinned is the presence of a line, not a data structure.
 */
const config = readFileSync(fileURLToPath(new URL('../lefthook.yml', import.meta.url)), 'utf8')

/** The body of one top-level hook block, e.g. everything indented under `pre-push:`. */
const hookBlock = name => {
  const start = config.indexOf(`\n${name}:\n`)
  assert.notEqual(start, -1, `lefthook.yml declares no ${name} hook`)
  const rest = config.slice(start + name.length + 3)
  const end = rest.search(/\n[a-z][a-z-]*:\n/)
  return end === -1 ? rest : rest.slice(0, end)
}

describe('lefthook.yml', () => {
  /**
   * Verified by experiment against a throwaway bare remote, not assumed: lefthook does **not** hand
   * a job the pre-push refspec lines unless the job asks for them. Without this line the guard reads
   * an empty stdin, finds no protected ref, and allows every push - which looks exactly like a guard
   * with nothing to refuse (#63).
   */
  test('lets the pre-push branch guard read the refs being pushed', () => {
    const prePush = hookBlock('pre-push')
    const job = prePush.slice(prePush.indexOf('- name: protected-branch'))

    assert.match(
      job.slice(0, job.indexOf('- name:', 1) === -1 ? undefined : job.indexOf('- name:', 1)),
      /use_stdin: true/,
      'the pre-push protected-branch job must set use_stdin, or it reads nothing'
    )
  })

  test('runs the branch guard on commit as well, where stdin is not involved', () => {
    assert.match(hookBlock('pre-commit'), /- name: protected-branch/)
  })
})
