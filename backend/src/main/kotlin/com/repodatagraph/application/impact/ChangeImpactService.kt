package com.repodatagraph.application.impact

import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CandidatePath
import com.repodatagraph.domain.model.ChangeImpactQuery
import com.repodatagraph.domain.model.ChangeImpactResult
import com.repodatagraph.domain.model.ChangeScope
import com.repodatagraph.domain.model.Citation
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactDirection
import com.repodatagraph.domain.model.ImpactHit
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.PathFilter
import com.repodatagraph.domain.model.PathIndexEntry
import com.repodatagraph.domain.model.PathIndexKind
import com.repodatagraph.domain.port.`in`.ChangeImpactUseCase
import com.repodatagraph.domain.port.out.ChangeLineagePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.springframework.stereotype.Service

/**
 * What a change to a repository reaches, ranked for an agent's context pack (#87).
 *
 * #21's machinery does the walking: the registry's downstream traversal ([TraversalFilterBuilder]),
 * found by [ImpactQueryPort], and owners by [OwnerResolver]. This adds what an agent needs on top -
 * each hit placed in the environment it runs in, scored by [ImpactScorer], ranked in a total order,
 * cut at the limit, and cited - and says honestly what it could not do: a `paths` filter with no
 * index to read it against, and a `sha` that names no Change (#85) to scope it by. A sha that does
 * name one leaves out every deployment whose artifact does not contain it, and whatever the walk
 * reached only through those.
 */
@Service
class ChangeImpactService(
    private val queries: ImpactQueryPort,
    private val graphStore: GraphStore,
    private val traversals: TraversalFilterBuilder,
    private val ownerResolver: OwnerResolver,
    private val lineage: ChangeLineagePort,
) : ChangeImpactUseCase {
    override fun changeImpact(query: ChangeImpactQuery): ChangeImpactResult {
        val repository = graphStore.findNode(query.repository) ?: throw NodeNotFoundException(listOf(query.repository))
        val search = queries.paths(repository.key, traversals.impact(ImpactDirection.DOWNSTREAM), query.depth, MAX_PATHS)
        val scope = shaScope(repository.key, query.sha)
        val nearest = ImpactScorer.nearest(repository.key, scope.keep(search.paths))
        val placements = queries.placements(nearest.map { it.target.key }, traversals.placement())
        val paths = pathMatch(repository.key, query.paths)

        val ranked = ImpactScorer.rank(nearest.map { hit(it, placements[it.target.key].orEmpty(), paths.named) })
        val kept = ranked.take(query.limit)
        val ownerPaths =
            queries.ownerPathsOf(
                kept.map { it.node.key },
                traversals.ownershipInheritance(),
                traversals.ownerEdges(),
                INHERITANCE_DEPTH,
            )

        return ChangeImpactResult(
            repository = repository,
            depth = query.depth,
            limit = query.limit,
            pathFilter = paths.filter,
            matchedPaths = paths.matched,
            changeScope = scope.scope,
            truncated = search.truncated || ranked.size > query.limit,
            hits = kept.map { it.copy(owners = ownerResolver.resolve(it.node, ownerPaths[it.node.key].orEmpty()).owners) },
        )
    }

    /**
     * What a sha narrows the walk to (#87, FR3): nothing when none was asked or it names no Change in
     * the repository, else the deployments whose artifact contains one of the Changes it names.
     */
    private fun shaScope(
        repository: NodeKey,
        sha: String?,
    ): ShaScope {
        val changes = sha?.let { lineage.changesMatching(repository, it.lowercase()) }
        return when {
            changes == null -> ShaScope(ChangeScope.NOT_REQUESTED)
            changes.isEmpty() -> ShaScope(ChangeScope.UNKNOWN)
            else -> ShaScope(ChangeScope.APPLIED, lineage.deploymentsCarrying(changes, traversals.lineage()))
        }
    }

    /**
     * A sha's scope: when applied, a path through a deployment not among [carrying] is dropped, so a
     * node reached only through such a deployment - its environment - is dropped with it.
     */
    private data class ShaScope(
        val scope: ChangeScope,
        val carrying: Set<NodeKey> = emptySet(),
    ) {
        fun keep(paths: List<CandidatePath>): List<CandidatePath> {
            if (scope != ChangeScope.APPLIED) return paths
            val carried = carrying.mapTo(hashSetOf()) { it.id }
            return paths.filter { path -> path.steps.none { isDeployment(it.to) && it.to !in carried } }
        }

        private fun isDeployment(id: String): Boolean = id.startsWith("$DEPLOYMENT:")
    }

    private fun hit(
        path: CandidatePath,
        placedIn: List<GraphNode>,
        namedByMatchedPaths: Set<String>,
    ): ImpactHit {
        val node = path.target
        val environment = environmentOf(node, placedIn)
        val tier = environment?.let { EnvironmentTier.of(it.props[TIER]) } ?: EnvironmentTier.OTHER
        val matched = namesOf(node).any { it in namedByMatchedPaths }
        return ImpactHit(
            node = node,
            hops = path.steps.size,
            score = ImpactScorer.score(path.steps.size, tier, matched),
            confidence = PathConfidence.of(path.steps),
            inferred = path.steps.any { it.inferred },
            environment = environment,
            tier = tier,
            pathMatched = matched,
            owners = emptyList(),
            citation = Citation(node.id, path.steps, node.provenance),
        )
    }

    /** An Environment is its own; anything else runs in the most critical environment it is placed in. */
    private fun environmentOf(
        node: GraphNode,
        placedIn: List<GraphNode>,
    ): GraphNode? {
        if (node.type == ENVIRONMENT) return node
        return placedIn
            .filter { it.type == ENVIRONMENT }
            .minWithOrNull(compareBy<GraphNode> { EnvironmentTier.of(it.props[TIER]).ordinal }.thenBy { it.id })
    }

    /** What an index entry could name a node by: its key, or the resource id or name it has. */
    private fun namesOf(node: GraphNode): Set<String> =
        setOfNotNull(node.key.key, node.props["resourceId"]?.toString(), node.props["name"]?.toString())

    private fun pathMatch(
        repository: NodeKey,
        requested: List<String>,
    ): PathMatch {
        val index = if (requested.isEmpty()) emptyList() else queries.pathIndex(repository)
        return when {
            requested.isEmpty() -> PathMatch(PathFilter.NOT_REQUESTED)
            index.isEmpty() -> PathMatch(PathFilter.NOT_APPLIED)
            else -> {
                val wanted = requested.map(::normalise).toSet()
                val matching: List<PathIndexEntry> = index.filter { normalise(it.path) in wanted }
                PathMatch(
                    filter = PathFilter.APPLIED,
                    matched = matching.map { normalise(it.path) }.distinct().sorted(),
                    // A manifest names what the repository depends on, which is upstream of a change
                    // and so never a hit; only what an IaC file names can be.
                    named = matching.filter { it.kind == PathIndexKind.IAC }.flatMapTo(mutableSetOf()) { it.names },
                )
            }
        }
    }

    private fun normalise(path: String): String = path.trim().removePrefix("./").trimStart('/')

    private data class PathMatch(
        val filter: PathFilter,
        val matched: List<String> = emptyList(),
        val named: Set<String> = emptySet(),
    )

    private companion object {
        const val ENVIRONMENT = "Environment"
        const val DEPLOYMENT = "Deployment"
        const val TIER = "tier"

        /** As #21's blast radius: candidate paths read before the walk is cut short. */
        const val MAX_PATHS = 50_000

        /** As #21's owners: deployment, artifact, repository, with room for one more. */
        const val INHERITANCE_DEPTH = 4
    }
}
