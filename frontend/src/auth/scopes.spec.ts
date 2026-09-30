import { describe, expect, it } from 'vitest'
import { graphScopesOf, refusalReason } from './scopes'
import { accessTokenWith } from '@/test/authSession'

/**
 * What the signed-in user may do, read from the graph scopes on their access token (#116). The web
 * interface uses it only to decide what to offer: the API checks the same token on every request, so
 * a token read wrongly here costs a refused request, never a write that should not happen.
 */
describe('graphScopesOf', () => {
  it('reads the graph scopes from the space-separated scope claim, sorted', () => {
    expect(
      graphScopesOf(accessTokenWith({ scope: 'openid graph:write profile graph:read' }))
    ).toEqual(['graph:read', 'graph:write'])
  })

  it('reads an scp array as well', () => {
    expect(graphScopesOf(accessTokenWith({ scp: ['graph:read', 'email'] }))).toEqual(['graph:read'])
  })

  it('decodes a token whose payload uses the URL-safe alphabet', () => {
    // Characters chosen so the encoded payload contains both - and _.
    const token = accessTokenWith({ name: 'Zoë ~~~ ???', scope: 'graph:read' })
    expect(token.split('.')[1]).toMatch(/[-_]/)

    expect(graphScopesOf(token)).toEqual(['graph:read'])
  })

  it('holds nothing when there is no token, or it is not a JWT', () => {
    expect(graphScopesOf(null)).toEqual([])
    expect(graphScopesOf('opaque-token')).toEqual([])
    expect(graphScopesOf('a.!!!.c')).toEqual([])
  })

  it('holds nothing when the token names no graph scope', () => {
    expect(graphScopesOf(accessTokenWith({ scope: 'openid profile' }))).toEqual([])
  })
})

describe('refusalReason', () => {
  it('says which scope a refused request needed and which the user holds', () => {
    const reason = refusalReason({
      error: 'insufficient scope',
      required: ['graph:write'],
      held: ['graph:read']
    })

    expect(reason).toBe(
      'You do not have permission to do that: it needs graph:write, and you hold graph:read.'
    )
  })

  it('says so when the user holds no graph scope at all', () => {
    expect(refusalReason({ error: 'insufficient scope', required: ['graph:read'], held: [] })).toBe(
      'You do not have permission to do that: it needs graph:read, and you hold no graph scope.'
    )
  })

  it('leaves every other refusal to the page that got it', () => {
    expect(refusalReason({ error: 'node exists' })).toBeNull()
    expect(refusalReason(undefined)).toBeNull()
  })
})
