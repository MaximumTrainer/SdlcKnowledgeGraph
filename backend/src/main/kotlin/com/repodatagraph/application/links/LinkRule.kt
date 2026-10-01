package com.repodatagraph.application.links

import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.ontology.EnvironmentAliases
import com.repodatagraph.domain.port.out.GraphStore

/**
 * One way of telling which repository owns a cloud resource (#28, FR1). A rule reads the evidence
 * the graph already holds about [GraphNode] - a CloudResource - and proposes repositories, each with
 * the rule's confidence and the evidence it found, so the ownership can be explained.
 *
 * A rule writes nothing; the engine decides between proposals and writes. Adding a rule is a class
 * implementing this and a bean in `LinksConfig` (docs/ONTOLOGY.md).
 */
interface LinkRule {
    /** The rule's name as `rule` on the edges it leads to: manual, tag, deployment, iac, naming. */
    val name: String

    fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal>
}

/** A rule's claim that [repoKey] owns the resource, at [confidence], because of [evidence]. */
data class LinkProposal(
    val repoKey: String,
    val confidence: Double,
    val rule: String,
    val evidence: Map<String, String>,
)

/** What rules may read, for one resolution: the graph, and the reads that span more than a node. */
interface LinkContext {
    val graphStore: GraphStore
    val environments: EnvironmentAliases

    /** The repository at [key], or null when the graph does not hold it. */
    fun repository(key: String): GraphNode?

    fun repositories(): List<GraphNode>

    fun iacFiles(): List<GraphNode>

    fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence>
}
