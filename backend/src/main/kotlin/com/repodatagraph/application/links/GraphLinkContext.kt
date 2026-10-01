package com.repodatagraph.application.links

import com.repodatagraph.domain.model.DeploymentEvidence
import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.ontology.EnvironmentAliases
import com.repodatagraph.domain.port.out.GraphStore
import com.repodatagraph.domain.port.out.LinkQueries

/**
 * A [LinkContext] over the graph, for one resolution. Repositories and IaC files are read once, on
 * first use, and held for the run: every resource asks for them, and a run is short enough that a
 * write landing during it is picked up by the next.
 */
class GraphLinkContext(
    override val graphStore: GraphStore,
    private val queries: LinkQueries,
    override val environments: EnvironmentAliases,
) : LinkContext {
    private val repositoriesByKey: Map<String, GraphNode> by lazy {
        all(REPOSITORY).filter { it.provenance.current }.associateBy { it.key.key }
    }

    private val files: List<GraphNode> by lazy { all(IAC_FILE) }

    override fun repository(key: String): GraphNode? = repositoriesByKey[key]

    override fun repositories(): List<GraphNode> = repositoriesByKey.values.toList()

    override fun iacFiles(): List<GraphNode> = files

    override fun deploymentsTargeting(resourceKey: String): List<DeploymentEvidence> = queries.deploymentsTargeting(resourceKey)

    private fun all(type: String): List<GraphNode> {
        val found = mutableListOf<GraphNode>()
        var after: String? = null
        do {
            val page = graphStore.findNodes(type, afterKey = after, limit = PAGE)
            found += page
            after = page.lastOrNull()?.key?.key
        } while (page.size == PAGE)
        return found
    }

    companion object {
        const val PAGE = 500
        const val REPOSITORY = "Repository"
        const val IAC_FILE = "IacFile"

        fun key(repoKey: String) = NodeKey(REPOSITORY, repoKey)
    }
}
