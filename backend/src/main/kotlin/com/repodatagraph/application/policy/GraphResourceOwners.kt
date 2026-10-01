package com.repodatagraph.application.policy

import com.repodatagraph.domain.model.Direction
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.port.`in`.ResourceOwners
import com.repodatagraph.domain.port.out.GraphStore
import org.springframework.stereotype.Service

/**
 * The teams that own a node (#30 FR7), read from the graph: those it is `OWNED_BY`, and for a cloud
 * resource with none of its own, those that own a repository or service that `OWNS_RESOURCE` it, as
 * ownership is inherited for impact (#21). A team owns itself, so its members may curate its node.
 *
 * Asked only when owning the node could change the policy's answer (ScopeGate), so most requests
 * read nothing here.
 */
@Service
class GraphResourceOwners(
    private val graphStore: GraphStore,
) : ResourceOwners {
    override fun ownerTeams(
        type: String,
        key: String,
    ): Set<String> {
        if (type.isBlank() || key.isBlank()) return emptySet()
        val node = NodeKey(type, key)
        return when (type) {
            TEAM -> setOf(key.lowercase())
            CLOUD_RESOURCE ->
                teamsOwning(node).ifEmpty {
                    graphStore
                        .findEdges(node, Direction.INCOMING, OWNS_RESOURCE)
                        .flatMapTo(sortedSetOf()) { teamsOwning(it.other.key) }
                }
            else -> teamsOwning(node)
        }
    }

    private fun teamsOwning(node: NodeKey): Set<String> =
        graphStore
            .findEdges(node, Direction.OUTGOING, OWNED_BY)
            .filter { it.other.type == TEAM }
            .mapTo(sortedSetOf()) {
                it.other.key.key
                    .lowercase()
            }

    private companion object {
        const val TEAM = "Team"
        const val CLOUD_RESOURCE = "CloudResource"
        const val OWNED_BY = "OWNED_BY"
        const val OWNS_RESOURCE = "OWNS_RESOURCE"
    }
}
