package com.repodatagraph.application.impact

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.ImpactResult
import com.repodatagraph.domain.model.ImpactSpec
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnersResult
import com.repodatagraph.domain.model.WhyFailedResult
import com.repodatagraph.domain.model.nodeIdParameter
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.ImpactUseCase
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.springframework.stereotype.Service

/**
 * The blast radius, why a deployment failed and who owns a node (#21).
 *
 * It resolves the node, asks the registry which edges to walk, has [ImpactQueryPort] find the paths
 * and decides what they mean. No query language here (FR9): the port's adapter holds the Cypher.
 */
@Service
class ImpactAnalysisService(
    private val queries: ImpactQueryPort,
    private val graphStore: GraphStore,
    private val registry: OntologyRegistry,
    private val traversals: TraversalFilterBuilder,
    private val analyser: WhyFailedAnalyser,
    private val ownerResolver: OwnerResolver,
) : ImpactUseCase {
    override fun impact(spec: ImpactSpec): ImpactResult {
        val root = resolve(spec.nodeId, "nodeId")
        val search = queries.paths(root.key, traversals.impact(spec.direction), spec.depth, MAX_PATHS)
        val ranking = PathConfidence.rank(root.key, search.paths, spec.minConfidence, ImpactSpec.MAX_AFFECTED)
        return ImpactResult(
            root = root,
            depth = spec.depth,
            direction = spec.direction,
            minConfidence = spec.minConfidence,
            truncated = search.truncated || ranking.truncated,
            affected = ranking.affected,
            byType = ranking.byType,
        )
    }

    /** [deploymentId] may be a full `Deployment:key` id or the bare key. */
    override fun whyDeploymentFailed(deploymentId: String): WhyFailedResult {
        val key = deploymentKey(deploymentId)
        val facts = queries.deploymentFacts(key, DEPENDENCY_DEPTH) ?: throw NodeNotFoundException(listOf(key))
        return analyser.analyse(facts)
    }

    override fun owners(nodeId: String): OwnersResult {
        val node = resolve(nodeIdParameter("nodeId", nodeId), "nodeId")
        val paths = queries.ownerPaths(node.key, traversals.ownershipInheritance(), traversals.ownerEdges(), INHERITANCE_DEPTH)
        return ownerResolver.resolve(node, paths)
    }

    private fun resolve(
        key: NodeKey,
        field: String,
    ): GraphNode {
        if (!registry.isKnownNodeType(key.type)) {
            throw InvalidQueryParameterException(field, "unknown node type '${key.type}'")
        }
        return graphStore.findNode(key) ?: throw NodeNotFoundException(listOf(key))
    }

    private fun deploymentKey(deploymentId: String): NodeKey {
        if (deploymentId.isBlank()) throw InvalidQueryParameterException(FIELD_DEPLOYMENT, "deploymentId is required")
        val prefix = "$DEPLOYMENT:"
        if (deploymentId.startsWith(prefix)) return NodeKey(DEPLOYMENT, deploymentId.removePrefix(prefix))
        // A bare key, unless it names another type: a Deployment key starts with an artifact's
        // registry or name, never with a declared type followed by a colon.
        val type = deploymentId.substringBefore(':', missingDelimiterValue = "")
        if (registry.isKnownNodeType(type)) {
            throw InvalidQueryParameterException(FIELD_DEPLOYMENT, "deploymentId must name a Deployment, not a $type")
        }
        return NodeKey(DEPLOYMENT, deploymentId)
    }

    private companion object {
        const val DEPLOYMENT = "Deployment"
        const val FIELD_DEPLOYMENT = "deploymentId"

        /** Candidate paths read before the walk is cut short; far above what the listing cap needs on a sane graph. */
        const val MAX_PATHS = 50_000

        /** FR5: dependencies whose deployments count as changes are at most two hops away. */
        const val DEPENDENCY_DEPTH = 2

        /** Deployment, artifact, repository: the longest inheritance chain the registry has today, with room for one more. */
        const val INHERITANCE_DEPTH = 4
    }
}
