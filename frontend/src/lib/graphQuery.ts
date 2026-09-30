import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { GraphDirection } from '@/services/api'

/**
 * The graph view's filters, kept in the query string so a view someone narrowed down is a link they
 * can send (#9, FR6). Only what differs from the defaults is written, and a value that cannot be
 * used - a depth of 9, a direction of "sideways" - reads as its default rather than failing.
 */
export type Depth = 1 | 2 | 3

export interface GraphFilters {
  depth: Depth
  /** Empty means every type the view offers: no filter. */
  nodeTypes: string[]
  edgeTypes: string[]
  direction: GraphDirection
}

export const DEFAULT_FILTERS: GraphFilters = Object.freeze({
  depth: 1,
  nodeTypes: [],
  edgeTypes: [],
  direction: 'both'
}) as GraphFilters

const DIRECTIONS: GraphDirection[] = ['in', 'out', 'both']

const single = (value: LocationQueryRaw[string]): string | undefined => {
  const first = Array.isArray(value) ? value[0] : value
  return first === null || first === undefined ? undefined : String(first)
}

const list = (value: LocationQueryRaw[string]): string[] =>
  (single(value) ?? '')
    .split(',')
    .map(name => name.trim())
    .filter(name => name.length > 0)

export const filtersFromQuery = (query: LocationQueryRaw): GraphFilters => {
  const depth = Number(single(query.depth))
  const direction = single(query.direction) as GraphDirection | undefined
  return {
    depth: [1, 2, 3].includes(depth) ? (depth as Depth) : DEFAULT_FILTERS.depth,
    nodeTypes: list(query.nodeTypes),
    edgeTypes: list(query.edgeTypes),
    direction: direction && DIRECTIONS.includes(direction) ? direction : DEFAULT_FILTERS.direction
  }
}

/** [keep]'s other parameters - `e2e`, for one - survive; the filters' own are rewritten. */
export const filtersToQuery = (filters: GraphFilters, keep: LocationQuery): LocationQueryRaw => {
  const rest = Object.fromEntries(
    Object.entries(keep).filter(
      ([name]) => !['depth', 'nodeTypes', 'edgeTypes', 'direction'].includes(name)
    )
  )
  return {
    ...rest,
    ...(filters.depth !== DEFAULT_FILTERS.depth ? { depth: String(filters.depth) } : {}),
    ...(filters.nodeTypes.length ? { nodeTypes: filters.nodeTypes.join(',') } : {}),
    ...(filters.edgeTypes.length ? { edgeTypes: filters.edgeTypes.join(',') } : {}),
    ...(filters.direction !== DEFAULT_FILTERS.direction ? { direction: filters.direction } : {})
  }
}

/**
 * Ticks or unticks [type] among [all] the types on offer, where an empty [selected] means all of
 * them. The result keeps the order they are offered in, is empty again once every type is ticked,
 * and never unticks the last one: no type at all would draw nothing but the root.
 */
export const toggleType = (
  selected: string[],
  all: string[],
  type: string,
  on: boolean
): string[] => {
  const current = new Set(selected.length ? selected : all)
  if (on) current.add(type)
  else if (current.size > 1) current.delete(type)
  const next = all.filter(name => current.has(name))
  return next.length === all.length ? [] : next
}
