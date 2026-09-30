package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.DeploymentFacts
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.OwnerPath
import com.repodatagraph.domain.model.PathSearch
import com.repodatagraph.domain.model.Traversal

/**
 * The multi-hop reads behind impact analysis (#21, FR9). The application decides which edges to walk
 * (from the registry) and what the paths mean; an adapter only finds them, so the query language
 * stays behind this port.
 */
interface ImpactQueryPort {
    /**
     * Every path of 1 to [maxDepth] steps from [root] along [traversal], skipping closed facts, at most
     * [limit] of them; [PathSearch.truncated] says the limit was reached.
     */
    fun paths(
        root: NodeKey,
        traversal: Traversal,
        maxDepth: Int,
        limit: Int,
    ): PathSearch

    /**
     * Every path from [node] along 0 to [maxDepth] steps of [inheritance] and then one of [ownerEdges]:
     * the node's own owners, and the owners of what it inherits ownership from.
     */
    fun ownerPaths(
        node: NodeKey,
        inheritance: Traversal,
        ownerEdges: Set<String>,
        maxDepth: Int,
    ): List<OwnerPath>

    /**
     * The lineage of a deployment and the deployments around it, or null when there is no such
     * deployment. Dependencies are followed up to [dependencyDepth] hops.
     */
    fun deploymentFacts(
        deployment: NodeKey,
        dependencyDepth: Int,
    ): DeploymentFacts?
}
