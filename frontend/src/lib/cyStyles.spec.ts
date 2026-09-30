import { describe, expect, it } from 'vitest'
import type { Subgraph } from '@/services/api'
import { edgeOpacity, graphStylesheet, toElements } from './cyStyles'
import { ONTOLOGY_COLOURS } from './ontologyColours'

/**
 * How a subgraph is drawn (#9, FR4 and FR5): colour by type, label from the answer, and an inferred
 * edge dashed and as faint as it is uncertain, so a guess never looks like an observation.
 */
const provenance = {
  sourceSystem: 'manual',
  confidence: 1,
  inferred: false
} as Subgraph['nodes'][number]['provenance']
const PAYMENTS = 'Repository:github.com/acme/payments'
const BUCKET = 'CloudResource:aws:arn:aws:s3:::acme-logs'

const subgraph: Subgraph = {
  root: PAYMENTS,
  truncated: false,
  nodes: [
    {
      id: PAYMENTS,
      type: 'Repository',
      key: 'github.com/acme/payments',
      label: 'payments',
      distance: 0,
      props: {},
      provenance
    },
    {
      id: BUCKET,
      type: 'CloudResource',
      key: 'aws:arn:aws:s3:::acme-logs',
      label: 'acme-logs',
      distance: 1,
      props: {},
      provenance
    }
  ],
  edges: [
    {
      id: `OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`,
      type: 'OWNS_RESOURCE',
      inverse: 'OWNED_BY_REPO',
      from: PAYMENTS,
      to: BUCKET,
      confidence: 0.7,
      inferred: true
    },
    {
      id: `OWNED_BY:${PAYMENTS}>Team:platform`,
      type: 'OWNED_BY',
      inverse: 'OWNS',
      from: PAYMENTS,
      to: 'Team:platform',
      confidence: 1,
      inferred: false
    }
  ]
}

describe('edgeOpacity', () => {
  it('runs from 0.3 for no confidence to 1 for full confidence', () => {
    expect(edgeOpacity(0)).toBeCloseTo(0.3)
    expect(edgeOpacity(1)).toBeCloseTo(1)
    expect(edgeOpacity(0.5)).toBeCloseTo(0.65)
  })

  it('stays in range whatever it is given', () => {
    expect(edgeOpacity(-1)).toBeCloseTo(0.3)
    expect(edgeOpacity(7)).toBeCloseTo(1)
    expect(edgeOpacity(Number.NaN)).toBeCloseTo(1)
  })
})

describe('toElements', () => {
  const elements = toElements(subgraph)
  const byId = (id: string) => elements.find(element => element.data.id === id)

  it('draws a node with its label, type and colour, and marks the root', () => {
    expect(byId(PAYMENTS)).toMatchObject({
      group: 'nodes',
      data: {
        id: PAYMENTS,
        label: 'payments',
        type: 'Repository',
        colour: ONTOLOGY_COLOURS.Repository
      },
      classes: 'root'
    })
    expect(byId(BUCKET)?.classes ?? '').not.toContain('root')
  })

  it('draws an edge between its ends, labelled with its type', () => {
    expect(byId(`OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`)).toMatchObject({
      group: 'edges',
      data: { source: PAYMENTS, target: BUCKET, label: 'OWNS_RESOURCE' }
    })
  })

  it('marks only an inferred edge as inferred, so [inferred] selects exactly those', () => {
    expect(byId(`OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`)?.data.inferred).toBe(true)
    expect(byId(`OWNED_BY:${PAYMENTS}>Team:platform`)?.data).not.toHaveProperty('inferred')
  })

  it('fades an edge by its confidence', () => {
    expect(byId(`OWNS_RESOURCE:${PAYMENTS}>${BUCKET}`)?.data.opacity).toBeCloseTo(edgeOpacity(0.7))
    expect(byId(`OWNED_BY:${PAYMENTS}>Team:platform`)?.data.opacity).toBe(1)
  })
})

describe('graphStylesheet', () => {
  const rule = (selector: string) => graphStylesheet.find(entry => entry.selector === selector)

  it('dashes an inferred edge and leaves an observed one solid', () => {
    expect(rule('edge')?.style).toMatchObject({ 'line-style': 'solid' })
    expect(rule('edge[?inferred]')?.style).toMatchObject({ 'line-style': 'dashed' })
  })

  it('outlines what the blast radius reaches in red and dims the rest', () => {
    expect(rule('node.affected')?.style).toMatchObject({ 'border-color': '#e53e3e' })
    expect(rule('.dimmed')?.style).toHaveProperty('opacity')
  })
})
