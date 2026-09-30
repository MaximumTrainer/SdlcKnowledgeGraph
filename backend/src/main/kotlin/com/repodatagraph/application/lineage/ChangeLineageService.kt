package com.repodatagraph.application.lineage

import com.repodatagraph.application.impact.TraversalFilterBuilder
import com.repodatagraph.domain.exception.InvalidQueryParameterException
import com.repodatagraph.domain.exception.NodeNotFoundException
import com.repodatagraph.domain.model.CarriedChange
import com.repodatagraph.domain.model.CarriedDeployment
import com.repodatagraph.domain.model.CarriedWorkItem
import com.repodatagraph.domain.model.DeploymentWorkItems
import com.repodatagraph.domain.model.EnvironmentTier
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.LineageStatus
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.WorkItemDeployments
import com.repodatagraph.domain.ontology.IdentityResolver
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.`in`.ChangeLineageUseCase
import com.repodatagraph.domain.port.out.ChangeLineagePort
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.ImpactQueryPort
import org.springframework.stereotype.Service

/**
 * Where a work item is live, and what intent a deployment carries (#85).
 *
 * [ChangeLineagePort] finds the rows along the edges the registry's lineage names; this groups them,
 * places each deployment in its environment along the same placement edges impact uses, and orders
 * everything so the same graph always gives the same answer. A work item is found by its URI exactly
 * as written (ADR-0012), and a deployment whose artifact contains no change at all has an `unknown`
 * lineage rather than an empty one.
 */
@Service
class ChangeLineageService(
    private val graphStore: GraphStore,
    private val lineage: ChangeLineagePort,
    private val impactQueries: ImpactQueryPort,
    private val traversals: TraversalFilterBuilder,
    private val identityResolver: IdentityResolver,
    private val registry: OntologyRegistry,
) : ChangeLineageUseCase {
    override fun deploymentsOfWorkItem(uri: String?): WorkItemDeployments {
        if (uri.isNullOrBlank()) throw InvalidQueryParameterException(FIELD_URI, "uri is required")
        val key = identityResolver.keyFor(WORK_ITEM, mapOf(URI to uri))
        val workItem = graphStore.findNode(key) ?: throw NodeNotFoundException(listOf(key))
        val traversal = traversals.lineage()

        val byDeployment = lineage.carriersOf(workItem.key, traversal).groupBy { it.deployment.id }
        val environments = environmentsOf(byDeployment.values.map { it.first().deployment.key }, traversal.placement)
        val deployments =
            byDeployment.values.map { carriers ->
                val deployment = carriers.first().deployment
                CarriedDeployment(
                    deployment = deployment,
                    environment = environments[deployment.key],
                    artifacts = distinctById(carriers.map { it.artifact }),
                    changes = distinctById(carriers.map { it.change }),
                )
            }
        return WorkItemDeployments(
            workItem = workItem,
            deployments = deployments.sortedWith(compareByDescending<CarriedDeployment> { it.deployedAt }.thenBy { it.deployment.id }),
        )
    }

    override fun workItemsOfDeployment(deploymentId: String?): DeploymentWorkItems {
        val key = deploymentKey(deploymentId)
        val deployment = graphStore.findNode(key) ?: throw NodeNotFoundException(listOf(key))
        val traversal = traversals.lineage()
        val contents = lineage.contentsOf(deployment.key, traversal)

        val changes =
            contents
                .map { CarriedChange(it.change, it.artifact) }
                .distinctBy { it.change.id to it.artifact.id }
                .sortedWith(compareBy<CarriedChange> { it.change.id }.thenBy { it.artifact.id })
        val workItems =
            contents
                .flatMap { content -> content.workItems.map { it to content.change } }
                .groupBy({ it.first.id }, { it })
                .map { (_, pairs) -> CarriedWorkItem(pairs.first().first, distinctById(pairs.map { it.second })) }
                .sortedBy { it.workItem.id }

        return DeploymentWorkItems(
            deployment = deployment,
            environment = environmentsOf(listOf(deployment.key), traversal.placement)[deployment.key],
            lineage = if (contents.isEmpty()) LineageStatus.UNKNOWN else LineageStatus.KNOWN,
            changes = changes,
            workItems = workItems,
        )
    }

    /** Each deployment's environment: the most critical one it is placed in, should there be several. */
    private fun environmentsOf(
        deployments: List<NodeKey>,
        placement: Set<String>,
    ): Map<NodeKey, GraphNode> {
        if (deployments.isEmpty()) return emptyMap()
        return impactQueries
            .placements(deployments, placement)
            .mapNotNull { (deployment, placedIn) ->
                placedIn
                    .filter { it.type == ENVIRONMENT }
                    .minWithOrNull(compareBy<GraphNode> { EnvironmentTier.of(it.props[TIER]).ordinal }.thenBy { it.id })
                    ?.let { deployment to it }
            }.toMap()
    }

    private fun distinctById(nodes: List<GraphNode>): List<GraphNode> = nodes.distinctBy { it.id }.sortedBy { it.id }

    /** A deployment's id, or its bare key unless that names another type, as the deployment impact reads it. */
    private fun deploymentKey(deploymentId: String?): NodeKey {
        if (deploymentId.isNullOrBlank()) throw InvalidQueryParameterException(FIELD_DEPLOYMENT, "deploymentId is required")
        val prefix = "$DEPLOYMENT:"
        if (deploymentId.startsWith(prefix)) return NodeKey(DEPLOYMENT, deploymentId.removePrefix(prefix))
        val type = deploymentId.substringBefore(':', missingDelimiterValue = "")
        if (registry.isKnownNodeType(type)) {
            throw InvalidQueryParameterException(FIELD_DEPLOYMENT, "deploymentId must name a Deployment, not a $type")
        }
        return NodeKey(DEPLOYMENT, deploymentId)
    }

    private companion object {
        const val WORK_ITEM = "ExternalWorkItem"
        const val DEPLOYMENT = "Deployment"
        const val ENVIRONMENT = "Environment"
        const val URI = "uri"
        const val TIER = "tier"
        const val FIELD_URI = "uri"
        const val FIELD_DEPLOYMENT = "deploymentId"
    }
}
