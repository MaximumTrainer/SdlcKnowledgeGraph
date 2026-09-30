package com.repodatagraph.application.contextpack

import com.repodatagraph.application.neighbourhood.SubgraphMapper
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.CurrentDeployments
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.TemplateDef
import com.repodatagraph.domain.ontology.TemplateStepDef
import com.repodatagraph.domain.ontology.WalkableEdge
import com.repodatagraph.domain.port.out.GraphStore
import java.time.Instant

/**
 * One walk of a template from a start node (#96, FR-1 and FR-2): one store step per repeat of each
 * template step, from every node the step before it reached. It records every path it finds, and
 * the edges they took; which path explains a node, and which nodes the budget keeps, are decided
 * after. A trail never returns to a node already on it, so a cycle ends the trail rather than the
 * walk, and a node a step has reached from one origin is not walked again from it at a later repeat.
 */
internal class TemplateWalk(
    private val graphStore: GraphStore,
    private val registry: OntologyRegistry,
    private val start: GraphNode,
    private val asOf: Instant?,
) {
    /** Every path the walk found, the start's own empty one left out. */
    val paths = mutableListOf<CandidatePath>()

    /** Every edge a recorded path took, by its id. */
    val edges = linkedMapOf<String, GraphEdge>()

    /** Whether a store step, or the walk's own cap, stopped before finding everything. */
    var truncated = false
        private set

    fun walk(template: TemplateDef): TemplateWalk {
        val root = Trail(start, emptyList(), setOf(start.id), origin = start.id, anchor = start.id)
        template.steps.forEach { run(it, listOf(root)) }
        return this
    }

    /** Walks [step] from [inputs], then each step under it from what it reached. */
    private fun run(
        step: TemplateStepDef,
        inputs: List<Trail>,
    ) {
        val edge = registry.walkable(step.edge) ?: return
        val entering = inputs.map { it.copy(origin = it.node.id, anchor = it.origin) }
        val seen = entering.mapTo(hashSetOf()) { it.node.id to it.origin }
        val outputs = if (step.min == 0) entering.toMutableList() else mutableListOf()
        var frontier = entering
        for (repeat in 1..step.max) {
            val eligible = frontier.filter { it.node.type in edge.sources }
            if (eligible.isEmpty()) break
            var next = hop(eligible, step, edge).filter { seen.add(it.node.id to it.origin) }
            if (step.current) next = current(next)
            if (repeat >= step.min) {
                outputs += next
                record(next)
            }
            frontier = next
        }
        step.then.forEach { run(it, outputs) }
    }

    /** One store step along [edge] from each of [trails], each far node extending every trail it was found from. */
    private fun hop(
        trails: List<Trail>,
        step: TemplateStepDef,
        edge: WalkableEdge,
    ): List<Trail> {
        val byKey = trails.groupBy { it.node.key }
        val found =
            graphStore.neighbourhood(
                byKey.keys,
                NeighbourStep(
                    edgeTypes = setOf(edge.type.name),
                    nodeTypes = emptySet(),
                    direction = if (edge.alongStoredDirection) Direction.OUTGOING else Direction.INCOMING,
                    limit = ROWS_PER_STEP,
                    where = step.where,
                    asOf = asOf,
                ),
            )
        if (found.truncated) truncated = true
        return found.hops.flatMap { hop ->
            val near = if (hop.direction == Direction.OUTGOING) hop.edge.from else hop.edge.to
            byKey[near].orEmpty().filter { hop.other.id !in it.visited }.map { trail ->
                val taken =
                    PathStep(step.edge, trail.node.id, hop.other.id, hop.edge.provenance.confidence, hop.edge.provenance.inferred)
                trail.copy(node = hop.other, steps = trail.steps + taken, visited = trail.visited + hop.other.id, via = hop.edge)
            }
        }
    }

    /** Of the deployments reached from each anchor, the current ones alone (see [CurrentDeployments]). */
    private fun current(trails: List<Trail>): List<Trail> =
        trails.groupBy { it.anchor }.values.flatMap { reached ->
            val keep = CurrentDeployments.select(reached.map { it.node }.distinctBy { it.id }, asOf)
            reached.filter { it.node.id in keep }
        }

    private fun record(trails: List<Trail>) {
        trails.forEach { trail ->
            if (paths.size >= MAX_PATHS) {
                truncated = true
                return
            }
            paths += CandidatePath(trail.node, trail.steps)
            trail.via?.let { edges.putIfAbsent(SubgraphMapper.edgeId(it), it) }
        }
    }

    /**
     * Where a walk stands: the node, the path to it and the nodes on that path. [origin] is the node
     * the current template step began from, and [anchor] the one the step before it began from - the
     * repository whose artifacts' deployments a `current` step narrows.
     */
    private data class Trail(
        val node: GraphNode,
        val steps: List<PathStep>,
        val visited: Set<String>,
        val origin: String,
        val anchor: String,
        val via: GraphEdge? = null,
    )

    companion object {
        /** The rows one store step reads before it says it stopped short. */
        const val ROWS_PER_STEP = 10_000

        /** The paths one walk records before it says it stopped short: far past any budget, still bounded. */
        const val MAX_PATHS = 20_000
    }
}
