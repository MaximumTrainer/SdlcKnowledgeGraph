package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkContext
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.domain.model.GraphNode

/**
 * The deployment rule (#28, FR2): a pipeline deployed an artifact built from a repository onto the
 * resource - a Deployment naming it in `targetResourceKeys`, whose artifact is BUILT_FROM the
 * repository. Evidence of what actually runs there, so nearly as strong as a tag. Each repository is
 * proposed once, citing its latest deployment.
 */
class DeploymentLinkRule(
    private val confidence: Double = DEFAULT_CONFIDENCE,
) : LinkRule {
    override val name = "deployment"

    override fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal> =
        ctx
            .deploymentsTargeting(resource.key.key)
            .groupBy { it.repoKey }
            .toSortedMap()
            .map { (repoKey, deployments) ->
                val latest = deployments.maxBy { it.deployedAt?.toEpochMilli() ?: Long.MIN_VALUE }
                LinkProposal(
                    repoKey,
                    confidence,
                    name,
                    listOfNotNull(
                        "deployment" to latest.deploymentKey,
                        "artifact" to latest.artifactKey,
                        latest.environment?.let { "environment" to it },
                        latest.deployedAt?.let { "deployedAt" to it.toString() },
                    ).toMap(),
                )
            }

    companion object {
        const val DEFAULT_CONFIDENCE = 0.9
    }
}
