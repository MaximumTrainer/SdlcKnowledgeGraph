package com.repodatagraph.application.links.rules

import com.repodatagraph.application.links.LinkContext
import com.repodatagraph.application.links.LinkDecision
import com.repodatagraph.application.links.LinkProposal
import com.repodatagraph.application.links.LinkRule
import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.GraphEdge
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Provenance

/**
 * The manual rule (#28, FR2): an ownership a person stated, or a candidate a person accepted, at
 * 1.0, so nothing a rule infers outranks it. Only current OWNS_RESOURCE edges from a repository
 * count, and only those a person wrote: `rule: manual`, or the manual source.
 */
class ManualLinkRule(
    private val confidence: Double = Provenance.FULL_CONFIDENCE,
) : LinkRule {
    override val name = LinkDecision.MANUAL

    override fun evaluate(
        resource: GraphNode,
        ctx: LinkContext,
    ): List<LinkProposal> =
        ctx.graphStore
            .findEdges(resource.key, Direction.INCOMING, OWNS_RESOURCE)
            .map { it.edge }
            .filter { it.from.type == REPOSITORY && it.provenance.current && isManual(it) }
            .map { edge ->
                LinkProposal(
                    edge.from.key,
                    confidence,
                    name,
                    listOfNotNull(edge.props["acceptedBy"]?.let { "acceptedBy" to it.toString() }).toMap(),
                )
            }

    companion object {
        const val OWNS_RESOURCE = "OWNS_RESOURCE"
        const val REPOSITORY = "Repository"

        /** Written by a person: accepted or stated through the link endpoints, or through the edge API. */
        fun isManual(edge: GraphEdge): Boolean =
            edge.props["rule"] == LinkDecision.MANUAL || edge.provenance.sourceSystem == Provenance.MANUAL
    }
}
