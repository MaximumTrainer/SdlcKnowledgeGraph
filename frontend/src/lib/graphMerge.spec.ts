import { describe, expect, it } from 'vitest'
import type {
  ImpactResult,
  OntologyEdgeType,
  Subgraph,
  SubgraphEdge,
  SubgraphNode
} from '@/services/api'
import { impactOverlay, mergeSubgraphs } from './graphMerge'

/**
 * Expanding a node fetches its own neighbourhood and merges it into what is drawn (#9, FR7): nothing
 * already on the canvas is duplicated or replaced, and the answer says what was new, so only that is
 * added to the canvas and nothing already placed moves.
 */
const provenance = {
  sourceSystem: 'manual',
  confidence: 1,
  inferred: false
} as SubgraphNode['provenance']

const node = (id: string, distance = 1): SubgraphNode => ({
  id,
  type: id.split(':')[0],
  key: id.slice(id.indexOf(':') + 1),
  label: id.slice(id.lastIndexOf('/') + 1),
  distance,
  props: {},
  provenance
})

const edge = (type: string, from: string, to: string, inferred = false): SubgraphEdge => ({
  id: `${type}:${from}>${to}`,
  type,
  inverse: `${type}_INVERSE`,
  from,
  to,
  confidence: inferred ? 0.7 : 1,
  inferred
})

const PAYMENTS = 'Repository:github.com/acme/payments'
const SHARED = 'Repository:github.com/acme/shared-lib'
const CHECKOUT = 'Repository:github.com/acme/checkout'
const TEAM = 'Team:platform'

const current: Subgraph = {
  root: PAYMENTS,
  nodes: [node(PAYMENTS, 0), node(SHARED), node(TEAM)],
  edges: [edge('DEPENDS_ON', PAYMENTS, SHARED), edge('OWNED_BY', PAYMENTS, TEAM)],
  truncated: false
}

describe('mergeSubgraphs', () => {
  it('adds what is new and reports exactly that', () => {
    const expansion: Subgraph = {
      root: SHARED,
      nodes: [node(SHARED, 0), node(PAYMENTS), node(CHECKOUT)],
      edges: [edge('DEPENDS_ON', PAYMENTS, SHARED), edge('DEPENDS_ON', CHECKOUT, SHARED)],
      truncated: false
    }

    const { merged, added } = mergeSubgraphs(current, expansion)

    expect(merged.nodes.map(n => n.id)).toEqual([PAYMENTS, SHARED, TEAM, CHECKOUT])
    expect(merged.edges.map(e => e.id)).toEqual([
      `DEPENDS_ON:${PAYMENTS}>${SHARED}`,
      `OWNED_BY:${PAYMENTS}>${TEAM}`,
      `DEPENDS_ON:${CHECKOUT}>${SHARED}`
    ])
    expect(added.nodes.map(n => n.id)).toEqual([CHECKOUT])
    expect(added.edges.map(e => e.id)).toEqual([`DEPENDS_ON:${CHECKOUT}>${SHARED}`])
  })

  it('keeps the root, and what it already had, as they were', () => {
    const renamed = { ...node(SHARED, 0), label: 'something else' }
    const { merged } = mergeSubgraphs(current, { ...current, root: SHARED, nodes: [renamed] })

    expect(merged.root).toBe(PAYMENTS)
    expect(merged.nodes.find(n => n.id === SHARED)?.label).toBe('shared-lib')
    expect(merged.nodes.find(n => n.id === SHARED)?.distance).toBe(1)
  })

  it('never loses a node, however little the expansion holds', () => {
    const { merged, added } = mergeSubgraphs(current, {
      root: TEAM,
      nodes: [node(TEAM, 0)],
      edges: [],
      truncated: false
    })

    expect(merged.nodes).toHaveLength(current.nodes.length)
    expect(merged.edges).toHaveLength(current.edges.length)
    expect(added.nodes).toEqual([])
  })

  it('drops an edge whose other end it was not given', () => {
    const { merged, added } = mergeSubgraphs(current, {
      root: SHARED,
      nodes: [node(SHARED, 0)],
      edges: [edge('DEPENDS_ON', CHECKOUT, SHARED)],
      truncated: false
    })

    expect(merged.edges.map(e => e.id)).not.toContain(`DEPENDS_ON:${CHECKOUT}>${SHARED}`)
    expect(added.edges).toEqual([])
  })

  it('stays truncated once either side was', () => {
    expect(mergeSubgraphs(current, { ...current, truncated: true }).merged.truncated).toBe(true)
    expect(mergeSubgraphs({ ...current, truncated: true }, current).merged.truncated).toBe(true)
  })
})

describe('impactOverlay', () => {
  const edgeTypes = [
    { name: 'DEPENDS_ON', inverse: 'DEPENDED_ON_BY' },
    { name: 'OWNS_RESOURCE', inverse: 'OWNED_BY_REPO' }
  ] as OntologyEdgeType[]

  const BUCKET = 'CloudResource:aws:arn:aws:s3:::acme-logs'
  const impact: ImpactResult = {
    root: { id: SHARED, type: 'Repository', key: 'github.com/acme/shared-lib' },
    depth: 2,
    direction: 'downstream',
    minConfidence: 0.5,
    truncated: false,
    affected: [
      {
        node: {
          id: PAYMENTS,
          type: 'Repository',
          key: 'github.com/acme/payments',
          props: { name: 'payments' }
        },
        distance: 1,
        confidence: 1,
        inferred: false,
        path: [
          { edge: 'DEPENDED_ON_BY', from: SHARED, to: PAYMENTS, confidence: 1, inferred: false }
        ]
      },
      {
        node: {
          id: BUCKET,
          type: 'CloudResource',
          key: 'aws:arn:aws:s3:::acme-logs',
          props: { name: 'acme-logs' }
        },
        distance: 2,
        confidence: 0.7,
        inferred: true,
        path: [
          { edge: 'DEPENDED_ON_BY', from: SHARED, to: PAYMENTS, confidence: 1, inferred: false },
          { edge: 'OWNS_RESOURCE', from: PAYMENTS, to: BUCKET, confidence: 0.7, inferred: true }
        ]
      }
    ],
    byType: { Repository: 1, CloudResource: 1, excluded: 0 }
  }

  it('names every affected node, and each path step as the edge it was stored as', () => {
    const overlay = impactOverlay(impact, edgeTypes, { Repository: 'name', CloudResource: 'name' })

    expect([...overlay.affected]).toEqual([PAYMENTS, BUCKET])
    // A step walked against the stored direction is the stored edge read backwards.
    expect(overlay.subgraph.edges.map(e => e.id)).toEqual([
      `DEPENDS_ON:${PAYMENTS}>${SHARED}`,
      `OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`
    ])
    expect(overlay.subgraph.edges[1]).toMatchObject({ inferred: true, confidence: 0.7 })
    expect(overlay.subgraph.nodes.find(n => n.id === BUCKET)?.label).toBe('acme-logs')
  })

  it('merges into what is drawn without duplicating what is already there', () => {
    const overlay = impactOverlay(impact, edgeTypes, {})
    const { added } = mergeSubgraphs(current, overlay.subgraph)

    expect(added.nodes.map(n => n.id)).toEqual([BUCKET])
    expect(added.edges.map(e => e.id)).toEqual([`OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`])
  })

  it('labels a node with its key when its type names no display property', () => {
    const overlay = impactOverlay(impact, edgeTypes, {})

    expect(overlay.subgraph.nodes.find(n => n.id === BUCKET)?.label).toBe(
      'aws:arn:aws:s3:::acme-logs'
    )
  })
})
