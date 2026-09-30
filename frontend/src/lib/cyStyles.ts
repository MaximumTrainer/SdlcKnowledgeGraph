import type { ElementDefinition } from 'cytoscape'
import type { Subgraph } from '@/services/api'
import { colourFor } from './ontologyColours'

/**
 * How the graph view draws a subgraph (#9, FR4 and FR5).
 *
 * A node takes its type's colour and the label the API gave it; an edge is labelled with its type.
 * An inferred edge is dashed and as faint as it is uncertain - opacity runs from 0.3 at no confidence
 * to 1 at full - so a link a rule guessed never looks like one a system reported. Only an inferred
 * edge carries `inferred` in its data, so `[inferred]` and `[?inferred]` both select exactly those.
 */
const MIN_OPACITY = 0.3

export const edgeOpacity = (confidence: number): number => {
  const bounded = Number.isFinite(confidence) ? Math.min(1, Math.max(0, confidence)) : 1
  return MIN_OPACITY + (1 - MIN_OPACITY) * bounded
}

export const toElements = (subgraph: Subgraph): ElementDefinition[] => [
  ...subgraph.nodes.map(node => ({
    group: 'nodes' as const,
    data: { id: node.id, label: node.label, type: node.type, colour: colourFor(node.type) },
    classes: node.id === subgraph.root ? 'root' : ''
  })),
  ...subgraph.edges.map(edge => ({
    group: 'edges' as const,
    data: {
      id: edge.id,
      source: edge.from,
      target: edge.to,
      label: edge.type,
      confidence: edge.confidence,
      opacity: edge.inferred ? edgeOpacity(edge.confidence) : 1,
      ...(edge.inferred ? { inferred: true } : {})
    }
  }))
]

export const AFFECTED_COLOUR = '#e53e3e'

/**
 * Plain rules rather than Cytoscape's own stylesheet type, whose per-property types cannot express
 * a `data(...)` mapping for every property; the view hands these to Cytoscape as they are.
 */
export interface StyleRule {
  selector: string
  style: Record<string, string | number | number[]>
}

export const graphStylesheet: StyleRule[] = [
  {
    selector: 'node',
    style: {
      'background-color': 'data(colour)',
      label: 'data(label)',
      color: '#1a202c',
      'font-size': 11,
      'text-valign': 'bottom',
      'text-margin-y': 4,
      'text-max-width': '140px',
      'text-wrap': 'ellipsis',
      width: 28,
      height: 28,
      'border-width': 0
    }
  },
  { selector: 'node.root', style: { 'border-width': 3, 'border-color': '#1a202c' } },
  { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#63b3ed' } },
  {
    selector: 'edge',
    style: {
      width: 1.5,
      'line-color': '#a0aec0',
      'target-arrow-color': '#a0aec0',
      'target-arrow-shape': 'triangle',
      'curve-style': 'bezier',
      'line-style': 'solid',
      opacity: 'data(opacity)',
      label: 'data(label)',
      'font-size': 8,
      color: '#718096',
      'text-rotation': 'autorotate',
      'text-background-color': '#ffffff',
      'text-background-opacity': 0.8
    }
  },
  { selector: 'edge[?inferred]', style: { 'line-style': 'dashed', 'line-dash-pattern': [6, 4] } },
  { selector: 'node.affected', style: { 'border-width': 4, 'border-color': AFFECTED_COLOUR } },
  {
    selector: 'edge.affected',
    style: { 'line-color': AFFECTED_COLOUR, 'target-arrow-color': AFFECTED_COLOUR }
  },
  { selector: 'node.blast-origin', style: { 'border-width': 4, 'border-color': '#1a202c' } },
  { selector: '.dimmed', style: { opacity: 0.2 } }
]
