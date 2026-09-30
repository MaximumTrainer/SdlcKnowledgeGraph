package com.repodatagraph.application.neighbourhood

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.NeighbourhoodSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.ReachedNode
import com.repodatagraph.domain.model.SubgraphView
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.NeighbourhoodUseCase
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.stereotype.Service

/**
 * The neighbourhood the graph view draws (#9, FR1 and FR2): a breadth-first walk from the root, one
 * store step per hop, so every node is answered at the distance it was first reached and the cap
 * keeps the nearest ones.
 *
 * Type filters constrain the walk, not only the answer: a node of a type left out is neither shown
 * nor walked through. With no node types named, the graph's own bookkeeping types (the registry's
 * `meta` ones, such as the sync runs that PRODUCED every node) are left out, since they would crowd
 * out the software being looked at; naming one brings it back.
 *
 * Once the depth is reached one more step joins up the last ring - the edges between nodes already
 * reached - without reaching anything new. Once more nodes than the cap are reached the walk stops:
 * everything further out is further than what it already has.
 */
@Service
class NeighbourhoodService(
    private val graphStore: GraphStore,
    private val registry: OntologyRegistry,
    private val mapper: SubgraphMapper,
) : NeighbourhoodUseCase {
    override fun neighbourhood(spec: NeighbourhoodSpec): SubgraphView {
        val step = step(spec)
        val root = resolve(spec.nodeId)
        val reached = linkedMapOf(root.id to ReachedNode(root, 0))
        val edges = mutableListOf<GraphEdge>()
        var truncated = false
        var frontier = listOf(root.key)

        for (distance in 1..spec.depth) {
            if (frontier.isEmpty() || reached.size > spec.limit) break
            val found = graphStore.neighbourhood(frontier, step)
            truncated = truncated || found.truncated
            val next = mutableListOf<NodeKey>()
            found.hops.forEach { hop ->
                edges += hop.edge
                if (hop.other.id !in reached) {
                    reached[hop.other.id] = ReachedNode(hop.other, distance)
                    next += hop.other.key
                }
            }
            frontier = next
        }

        if (frontier.isNotEmpty() && reached.size <= spec.limit) {
            val joined = graphStore.neighbourhood(frontier, step.copy(within = reached.keys.toSet()))
            truncated = truncated || joined.truncated
            joined.hops.forEach { edges += it.edge }
        }

        return mapper.map(root, reached.values.toList(), edges, spec.limit, truncated || reached.size > spec.limit)
    }

    /** Checks every type the spec names against the registry, and fills in the default node types. */
    private fun step(spec: NeighbourhoodSpec): NeighbourStep {
        spec.nodeTypes.firstOrNull { !registry.isKnownNodeType(it) }?.let {
            throw InvalidQueryParameterException("nodeTypes", "unknown node type '$it'")
        }
        spec.edgeTypes.firstOrNull { registry.edgeType(it) == null }?.let {
            throw InvalidQueryParameterException("edgeTypes", "unknown edge type '$it'")
        }
        val nodeTypes =
            spec.nodeTypes.ifEmpty {
                registry.allNodeTypes().filterNot { it.meta }.mapTo(linkedSetOf()) { it.name }
            }
        return NeighbourStep(
            edgeTypes = spec.edgeTypes,
            nodeTypes = nodeTypes,
            direction = spec.direction,
            limit = ROWS_PER_STEP,
        )
    }

    private fun resolve(key: NodeKey): GraphNode {
        if (!registry.isKnownNodeType(key.type)) {
            throw InvalidQueryParameterException("nodeId", "unknown node type '${key.type}'")
        }
        return graphStore.findNode(key) ?: throw NodeNotFoundException(listOf(key))
    }

    private companion object {
        /**
         * The most edges one step reads. Far above what the cap of 500 nodes needs on a sane graph,
         * and a bound on a step from a frontier of hubs; a step that reaches it marks the answer cut.
         */
        const val ROWS_PER_STEP = 10_000
    }
}
