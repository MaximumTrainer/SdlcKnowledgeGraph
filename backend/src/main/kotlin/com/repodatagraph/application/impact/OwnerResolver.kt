package com.repodatagraph.application.impact

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.Owner
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.OwnersResult
import org.springframework.stereotype.Component

/**
 * Who owns a node (#21, FR6), from the ownership paths the store found.
 *
 * The nearest owners answer: a node's own OWNED_BY when it has one, otherwise the owners of the
 * nearest things it inherits ownership from. A farther owner is not listed beside a nearer one,
 * because "the platform team owns the repository this was built from" is not news to someone who
 * asked about a resource its own team owns. Each team is listed once, by its most confident path.
 */
@Component
class OwnerResolver {
    fun resolve(
        node: GraphNode,
        paths: List<OwnerPath>,
    ): OwnersResult {
        val nearest = paths.minOfOrNull { it.via.size } ?: return OwnersResult(node, emptyList())
        val owners =
            paths
                .filter { it.via.size == nearest }
                .map { Owner(team = it.team, via = it.via, confidence = PathConfidence.of(it.via)) }
                .groupBy { it.team.id }
                .values
                .map { byTeam -> byTeam.maxBy { it.confidence } }
                .sortedWith(compareByDescending<Owner> { it.confidence }.thenBy { it.team.id })
        return OwnersResult(node, owners)
    }
}
