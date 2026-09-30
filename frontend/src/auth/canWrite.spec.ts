import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { useCanWrite } from './canWrite'
import { providing, READ_ONLY, READ_WRITE, sessionWith } from '@/test/authSession'
import type { AuthSession } from './session'

/** Whether the web interface offers ways to change the graph (#116, #118). */
const canWriteAs = (session: AuthSession | null): boolean => {
  let result = false
  mount(
    defineComponent({
      setup() {
        result = useCanWrite().value
        return () => h('div')
      }
    }),
    { global: { provide: providing(session) } }
  )
  return result
}

describe('useCanWrite', () => {
  it('offers writes to a user whose token holds graph:write', () => {
    expect(canWriteAs(sessionWith(READ_WRITE))).toBe(true)
  })

  it('offers none to a user who may only read', () => {
    expect(canWriteAs(sessionWith(READ_ONLY))).toBe(false)
  })

  it('offers none without a login, where the API refuses every write', () => {
    expect(canWriteAs(null)).toBe(false)
  })
})
