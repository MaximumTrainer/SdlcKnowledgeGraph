package com.repodatagraph.domain.identity

import com.repodatagraph.domain.model.GraphNode
import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.NodeTypeDef

/**
 * A property two nodes both hold and disagree on, where the registry says a merge must not decide
 * between them (#98): part of the type's merge scope or of its alias.
 */
data class MergeConflict(
    val field: String,
    val from: Any?,
    val into: Any?,
)

/**
 * What a merge did to the source's relationships: [moved] to the target, [collapsed] into an edge
 * the target already had with the same type and far end, or [dropped] because it joined the two
 * nodes being merged and would have become a loop.
 */
data class EdgeMoves(
    val moved: Int,
    val collapsed: Int,
    val dropped: Int,
)

/**
 * Everything the store needs to merge [source] into [target] in one transaction, decided before it
 * is asked: the values the target [gained], the source's alias properties it [released] to the
 * target, the target's [previousKeys] afterwards, and [by], the provenance the merge is recorded as.
 * [kept] names the properties where the target's value won, and is reported rather than written.
 */
data class MergePlan(
    val source: GraphNode,
    val target: GraphNode,
    val gained: Map<String, Any?>,
    val kept: List<String>,
    val released: List<String>,
    val previousKeys: List<String>,
    val by: Provenance,
)

/** What the store did with a [MergePlan]: the edges, how many earlier merges now point at the target, and the target after. */
data class MergeResult(
    val edges: EdgeMoves,
    val redirected: Int,
    val node: GraphNode,
)

/** A merge as its caller is told it (#98): what went where, and what the node that stays is now. */
data class MergeOutcome(
    val from: NodeKey,
    val into: NodeKey,
    val dryRun: Boolean,
    val node: GraphNode,
    val gained: List<String>,
    val kept: List<String>,
    val edges: EdgeMoves,
    val previousKeys: List<String>,
    val redirected: Int,
)

/**
 * What a merge of two nodes of one type does to their values (#98, FR-4), before anything touches
 * the graph. The target - the node merged into - wins every disagreement, and the source only fills
 * what the target lacks. What the registry says two nodes must share, the type's merge scope and its
 * alias, is never something one side wins: two different values there are two different things, and
 * the merge is refused.
 *
 * A value is held when it is present and not null; an empty list is a value, since a source that
 * reports none is saying so.
 */
object MergeRules {
    fun conflicts(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): List<MergeConflict> =
        (nodeType.mergeScope + nodeType.alias)
            .distinct()
            .mapNotNull { field ->
                val from = source.props[field]
                val into = target.props[field]
                MergeConflict(field, from, into).takeIf { from != null && into != null && from != into }
            }

    /** The values the target lacks and the source holds, in the order the registry declares them. */
    fun fills(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): Map<String, Any?> =
        describing(nodeType)
            .filter { source.props[it] != null && target.props[it] == null }
            .associateWith { source.props[it] }

    /** The properties both hold with different values, where the target's stays. */
    fun kept(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): List<String> =
        describing(nodeType).filter {
            val from = source.props[it]
            val into = target.props[it]
            from != null && into != null && from != into
        }

    /**
     * The source's alias properties that move to the target, which the source gives up in the same
     * transaction: an alias is unique, so the two cannot hold it at once.
     */
    fun released(
        nodeType: NodeTypeDef,
        source: GraphNode,
        target: GraphNode,
    ): List<String> = nodeType.alias.filter { source.props[it] != null && target.props[it] == null }

    /**
     * The keys the target has been known by once it absorbs the source: its own earlier keys, then the
     * source's key and the source's earlier keys, each once, and never the key it holds now.
     */
    fun previousKeys(
        source: GraphNode,
        target: GraphNode,
    ): List<String> =
        (target.provenance.previousKeys + source.key.key + source.provenance.previousKeys)
            .distinct()
            .filter { it != target.key.key }

    /** Declared properties that describe a node rather than identify it. */
    private fun describing(nodeType: NodeTypeDef): List<String> {
        val identifying = nodeType.identity.toSet() + DerivedProperties.identityBound(nodeType.name)
        return nodeType.properties.map { it.name }.filter { it !in identifying }
    }
}
