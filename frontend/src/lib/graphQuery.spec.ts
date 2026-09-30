import { describe, expect, it } from 'vitest'
import { DEFAULT_FILTERS, filtersFromQuery, filtersToQuery, toggleType } from './graphQuery'

/**
 * The graph view's filters live in the query string (#9, FR6), so a view someone narrowed down is a
 * link they can send. Defaults are left out of it, and anything unreadable falls back to them.
 */
describe('filtersFromQuery', () => {
  it('reads nothing as the defaults', () => {
    expect(filtersFromQuery({})).toEqual(DEFAULT_FILTERS)
    expect(DEFAULT_FILTERS).toEqual({ depth: 1, nodeTypes: [], edgeTypes: [], direction: 'both' })
  })

  it('reads every filter back', () => {
    expect(
      filtersFromQuery({
        depth: '3',
        nodeTypes: 'Repository,Team',
        edgeTypes: 'DEPENDS_ON',
        direction: 'in'
      })
    ).toEqual({
      depth: 3,
      nodeTypes: ['Repository', 'Team'],
      edgeTypes: ['DEPENDS_ON'],
      direction: 'in'
    })
  })

  it('falls back to the default for a value it cannot use', () => {
    expect(filtersFromQuery({ depth: '9', direction: 'sideways' })).toEqual(DEFAULT_FILTERS)
    expect(filtersFromQuery({ depth: 'two' }).depth).toBe(1)
    expect(filtersFromQuery({ nodeTypes: ',, ,' }).nodeTypes).toEqual([])
  })
})

describe('filtersToQuery', () => {
  it('writes only what differs from the defaults, and keeps other parameters', () => {
    expect(filtersToQuery(DEFAULT_FILTERS, { e2e: '1' })).toEqual({ e2e: '1' })
    expect(
      filtersToQuery(
        { depth: 2, nodeTypes: ['Repository', 'Team'], edgeTypes: [], direction: 'out' },
        { e2e: '1', depth: '1' }
      )
    ).toEqual({ e2e: '1', depth: '2', nodeTypes: 'Repository,Team', direction: 'out' })
  })

  it('round-trips', () => {
    const filters = {
      depth: 3 as const,
      nodeTypes: ['Team'],
      edgeTypes: ['OWNED_BY', 'DEPENDS_ON'],
      direction: 'in' as const
    }
    expect(filtersFromQuery(filtersToQuery(filters, {}))).toEqual(filters)
  })
})

describe('toggleType', () => {
  const all = ['Repository', 'Team', 'CloudResource']

  it('unticking one of all selects the rest, in the order they are offered', () => {
    expect(toggleType([], all, 'CloudResource', false)).toEqual(['Repository', 'Team'])
  })

  it('ticking the last one back means no filter at all', () => {
    expect(toggleType(['Repository', 'Team'], all, 'CloudResource', true)).toEqual([])
  })

  it('ticking and unticking within a selection keeps the offered order', () => {
    expect(toggleType(['Team'], all, 'Repository', true)).toEqual(['Repository', 'Team'])
    expect(toggleType(['Repository', 'Team'], all, 'Repository', false)).toEqual(['Team'])
  })

  it('the last ticked type stays ticked, since no type at all would draw nothing but the root', () => {
    expect(toggleType(['Team'], all, 'Team', false)).toEqual(['Team'])
  })
})
