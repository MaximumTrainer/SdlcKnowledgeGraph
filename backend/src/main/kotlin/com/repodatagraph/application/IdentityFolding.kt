package com.repodatagraph.application

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance

/**
 * Offered every node a writer has just stored (#98, FR-2), so a node known by a weaker key can be
 * folded into the one a stronger key now names. The writer's own write stands whatever this decides.
 */
fun interface IdentityFolding {
    fun afterWrite(node: GraphNode)

    companion object {
        /** Folds nothing: for a writer built without the registry's folding, such as in a unit test. */
        val NONE = IdentityFolding { }
    }
}

/**
 * Merges [from] into [into] because a write showed they are one thing, recorded as [by], the
 * provenance of that write (#98, FR-2). No principal asked for it, so none is checked; the folding
 * that calls it has decided already that the merge is safe.
 */
fun interface AutomaticMerge {
    fun fold(
        from: NodeKey,
        into: NodeKey,
        by: Provenance,
    )
}
