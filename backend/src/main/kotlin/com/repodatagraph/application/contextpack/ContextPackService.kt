package com.repodatagraph.application.contextpack

import com.repodatagraph.application.freshness.FactFreshness
import com.repodatagraph.application.impact.ImpactScorer
import com.repodatagraph.application.impact.PathConfidence
import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.application.neighbourhood.SubgraphMapper
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.ContextPack
import com.repodatagraph.domain.model.ContextPackQuery
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NeighbourStep
import com.repodatagraph.domain.model.PackEdge
import com.repodatagraph.domain.model.PackNode
import com.repodatagraph.domain.model.PathStep
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.model.ProvenanceSummary
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.ontology.TemplateDef
import com.repodatagraph.domain.port.`in`.ContextPackUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * The bounded subgraph a task needs (#96): a registry template walked from one node ([TemplateWalk]),
 * each node reached explained by its nearest path and scored as #87 scores an impact hit
 * ([ImpactScorer], ADR-0011), owners added where the template asks, then ranked
 * ([ContextPackRanking]) and cut to the budget, with every fact's provenance in one line.
 */
@Service
class ContextPackService(
    private val graphStore: GraphStore,
    private val registry: OntologyRegistry,
    private val traversals: TraversalFilterBuilder,
    private val impactQueries: ImpactQueryPort,
    private val mapper: SubgraphMapper,
    private val freshness: FactFreshness,
) : ContextPackUseCase {
    override fun contextPack(query: ContextPackQuery): ContextPack {
        val template = templateOf(query)
        val start = startOf(query)
        val walk = TemplateWalk(graphStore, registry, start, query.asOf).walk(template)
        val edges = LinkedHashMap(walk.edges)
        val paths = walk.paths.toMutableList()
        if (template.owners) paths += ownerPaths(start, ImpactScorer.nearest(start.key, paths), query.asOf, edges)

        val nearest = ImpactScorer.nearest(start.key, paths)
        val placements = impactQueries.placements(nearest.map { it.target.key }, traversals.placement())
        val cut = ContextPackRanking.cut(nearest.map { packNode(it, placements[it.target.key].orEmpty()) }, query.budget)
        val kept = cut.kept.mapTo(hashSetOf()) { it.node.id } + start.id

        return ContextPack(
            template = template.name,
            start = packNode(CandidatePath(start, emptyList()), emptyList()),
            budget = query.budget,
            asOf = query.asOf,
            reached = nearest.size,
            truncated = walk.truncated || cut.cut > 0,
            cut = cut.cut,
            nodes = cut.kept,
            edges =
                edges.values
                    .filter { it.from.id in kept && it.to.id in kept }
                    .sortedBy { SubgraphMapper.edgeId(it) }
                    .map { PackEdge(it, registry.inverseOf(it.type).orEmpty(), summary(it.provenance)) },
        )
    }

    /** The template asked for, or a refusal naming the ones there are. */
    private fun templateOf(query: ContextPackQuery): TemplateDef {
        val template =
            registry.template(query.template)
                ?: throw InvalidQueryParameterException(
                    TEMPLATE,
                    "unknown template '${query.template}'; known: ${registry.templates.joinToString { it.name }}",
                )
        requireStartType(template, query.startId.type)
        return template
    }

    /** A start of a node type the ontology declares, and one [template] starts from. */
    private fun requireStartType(
        template: TemplateDef,
        type: String,
    ) {
        if (!registry.isKnownNodeType(type)) {
            throw InvalidQueryParameterException(START_ID, "startId names node type '$type', which the ontology does not declare")
        }
        if (type !in template.start) {
            throw InvalidQueryParameterException(
                START_ID,
                "template '${template.name}' starts from ${template.start.joinToString(" or ")}, not $type",
            )
        }
    }

    /** The start node, as the graph holds it now or held it at the instant asked about. */
    private fun startOf(query: ContextPackQuery): GraphNode {
        val key = query.startId
        val asOf = query.asOf
        val found = if (asOf == null) graphStore.findNode(key) else graphStore.findNode(key, asOf)
        return found ?: throw NodeNotFoundException(listOf(key))
    }

    /**
     * The teams the owner edges of the start and of every node reached name, each one step past the
     * nearest path to what it owns. The edges are added to [edges] so the pack says who owns what.
     */
    private fun ownerPaths(
        start: GraphNode,
        reached: List<CandidatePath>,
        asOf: Instant?,
        edges: MutableMap<String, GraphEdge>,
    ): List<CandidatePath> {
        val pathTo = reached.associate { it.target.key to it.steps } + (start.key to emptyList())
        val found =
            graphStore.neighbourhood(
                pathTo.keys,
                NeighbourStep(
                    edgeTypes = traversals.ownerEdges(),
                    nodeTypes = emptySet(),
                    direction = Direction.OUTGOING,
                    limit = TemplateWalk.ROWS_PER_STEP,
                    asOf = asOf,
                ),
            )
        return found.hops.mapNotNull { hop ->
            val steps = pathTo[hop.edge.from] ?: return@mapNotNull null
            if (hop.other.id == start.id || steps.any { it.from == hop.other.id }) return@mapNotNull null
            edges.putIfAbsent(SubgraphMapper.edgeId(hop.edge), hop.edge)
            val owned =
                PathStep(hop.edge.type, hop.edge.from.id, hop.other.id, hop.edge.provenance.confidence, hop.edge.provenance.inferred)
            CandidatePath(hop.other, steps + owned)
        }
    }

    private fun packNode(
        path: CandidatePath,
        placedIn: List<GraphNode>,
    ): PackNode {
        val node = path.target
        val tier = ImpactScorer.tierOf(ImpactScorer.environmentOf(node, placedIn))
        return PackNode(
            node = node,
            label = mapper.label(node),
            distance = path.steps.size,
            confidence = PathConfidence.of(path.steps),
            inferred = path.steps.any { it.inferred },
            score = ImpactScorer.score(path.steps.size, tier, pathMatched = false),
            tier = tier,
            via = path.steps,
            provenance = summary(node.provenance),
        )
    }

    private fun summary(provenance: Provenance): ProvenanceSummary =
        ProvenanceSummary(
            source = provenance.sourceSystem,
            observedAt = provenance.observedAt,
            confidence = provenance.confidence,
            inferred = provenance.inferred,
            stale = freshness.stale(provenance),
        )

    private companion object {
        const val TEMPLATE = "template"
        const val START_ID = "startId"
    }
}
