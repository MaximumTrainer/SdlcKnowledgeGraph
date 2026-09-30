import type {
  ImpactResult,
  OntologyEdgeType,
  Subgraph,
  SubgraphEdge,
  SubgraphNode
} from '@/services/api'

/**
 * Merging what the graph view fetches into what it has drawn (#9, FR7 and FR8).
 *
 * Nodes and edges are identified by their ids - an edge's is `type:from>to` - so an expansion or a
 * blast radius that repeats what is drawn adds nothing, and what is new is reported separately so
 * the view adds only that to the canvas and leaves everything already placed where it is.
 */
export interface Merge {
  merged: Subgraph
  added: { nodes: SubgraphNode[]; edges: SubgraphEdge[] }
}

export const mergeSubgraphs = (current: Subgraph, incoming: Subgraph): Merge => {
  const known = new Set(current.nodes.map(node => node.id))
  const addedNodes = incoming.nodes.filter(node => {
    if (known.has(node.id)) return false
    known.add(node.id)
    return true
  })
  const edgeIds = new Set(current.edges.map(edge => edge.id))
  const addedEdges = incoming.edges.filter(edge => {
    if (edgeIds.has(edge.id) || !known.has(edge.from) || !known.has(edge.to)) return false
    edgeIds.add(edge.id)
    return true
  })
  return {
    merged: {
      root: current.root,
      nodes: [...current.nodes, ...addedNodes],
      edges: [...current.edges, ...addedEdges],
      truncated: current.truncated || incoming.truncated
    },
    added: { nodes: addedNodes, edges: addedEdges }
  }
}

/** What a blast radius adds to the canvas, and which nodes it reaches. */
export interface ImpactOverlay {
  subgraph: Subgraph
  affected: Set<string>
}

/**
 * The blast radius of #21 as a subgraph the view can merge: every affected node, and every step of
 * the paths that reached them as the edge it is stored as. A path names a step as it was walked, so a
 * step along an inverse (`DEPENDED_ON_BY` from a library to its dependent) is the stored edge read
 * backwards (`DEPENDS_ON` from the dependent to the library), and gets that edge's id.
 *
 * A node is labelled as the neighbourhood labels it: by its type's display property, or its key.
 */
export const impactOverlay = (
  impact: ImpactResult,
  edgeTypes: Pick<OntologyEdgeType, 'name' | 'inverse'>[],
  displayProperties: Record<string, string | null | undefined>
): ImpactOverlay => {
  const inverseOf = new Map(edgeTypes.map(type => [type.name, type.inverse]))
  const storedAs = new Map(edgeTypes.map(type => [type.inverse, type.name]))
  const label = (type: string, key: string, props: Record<string, unknown>) => {
    const property = displayProperties[type]
    const value = property ? props[property] : undefined
    const text = Array.isArray(value) ? value.join(', ') : value == null ? '' : String(value)
    return text.trim() === '' ? key : text
  }

  const nodes: SubgraphNode[] = impact.affected.map(hit => ({
    id: hit.node.id,
    type: hit.node.type,
    key: hit.node.key,
    label: label(hit.node.type, hit.node.key, hit.node.props),
    distance: hit.distance,
    // The impact answer carries no provenance of a node, so none is claimed; the drawer says so.
    props: hit.node.props
  }))

  const edges = new Map<string, SubgraphEdge>()
  impact.affected
    .flatMap(hit => hit.path)
    .forEach(step => {
      // A name the registry declares as an edge was walked as stored; an inverse's, backwards.
      const backwards = inverseOf.has(step.edge) ? undefined : storedAs.get(step.edge)
      const type = backwards ?? step.edge
      const [from, to] = backwards ? [step.to, step.from] : [step.from, step.to]
      const edge: SubgraphEdge = {
        id: `${type}:${from}>${to}`,
        type,
        inverse: inverseOf.get(type) ?? type,
        from,
        to,
        confidence: step.confidence,
        inferred: step.inferred
      }
      if (!edges.has(edge.id)) edges.set(edge.id, edge)
    })

  return {
    subgraph: {
      root: impact.root.id,
      nodes,
      edges: [...edges.values()],
      truncated: impact.truncated
    },
    affected: new Set(nodes.map(node => node.id))
  }
}
