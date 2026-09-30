package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.DeployedChange
import com.repodatagraph.domain.model.LineageTraversal
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.WorkItemCarrier

/**
 * The reads behind change lineage (#85): deployment to artifact to change to work item. The
 * application picks the edges from the registry ([LineageTraversal]); an adapter only finds the rows,
 * skipping closed facts, so the query language stays behind this port.
 */
interface ChangeLineagePort {
    /** One row per deployment, artifact and change that carries [workItem]; empty when nothing does. */
    fun carriersOf(
        workItem: NodeKey,
        lineage: LineageTraversal,
    ): List<WorkItemCarrier>

    /**
     * One row per change the artifacts of [deployment] contain, with the work items it implements.
     * Empty when no artifact of the deployment contains any change.
     */
    fun contentsOf(
        deployment: NodeKey,
        lineage: LineageTraversal,
    ): List<DeployedChange>

    /**
     * The Changes in [repository] that [sha] names: a stored sha it abbreviates, or one that abbreviates
     * it. [sha] is lower case, as changes are stored.
     */
    fun changesMatching(
        repository: NodeKey,
        sha: String,
    ): List<NodeKey>

    /** The deployments of every artifact that contains one of [changes]. */
    fun deploymentsCarrying(
        changes: Collection<NodeKey>,
        lineage: LineageTraversal,
    ): Set<NodeKey>
}
