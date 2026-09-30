package com.repodatagraph.adapter.out.neo4j

/**
 * How the earlier values of a node are kept (#33, FR1): a `NodeVersion` node per set of values
 * replaced, carrying the replaced values, the id of the node it is a version of, and the interval
 * those values held as its own validity. Linked by that id rather than by a relationship, so no
 * traversal of the current graph ever meets one and every current read is what it was before.
 */
internal object NodeVersions {
    const val LABEL = "NodeVersion"

    /** A version's own properties, which are not the values it keeps. */
    val BOOKKEEPING = setOf("versionOf", "since", "until", "retired", "retiredReason")

    /** Where an interval with no recorded beginning is taken to begin, as [ValidityCypher.holds] does. */
    const val EPOCH = "datetime('1970-01-01T00:00:00Z')"

    /**
     * The values [node] held at `$asOf`, as [out]: the newest version that held then, projected with
     * the node's own key and id and its bookkeeping set to null so a reader skips it, or else the
     * node's own when it held then, or else null. [carry] names the variables the caller keeps.
     */
    fun heldAt(
        node: String,
        out: String,
        carry: String = "",
    ): String {
        val kept = if (carry.isBlank()) node else "$carry, $node"
        val hidden = BOOKKEEPING.joinToString(", ") { "$it: null" }
        return """
            OPTIONAL MATCH (held:$LABEL { versionOf: $node.id }) WHERE ${ValidityCypher.holds("held")}
            WITH $kept, held ORDER BY held.since DESC
            WITH $kept, head(collect(held)) AS held
            WITH $kept, CASE
              WHEN held IS NOT NULL THEN held { .*, key: $node.key, id: $node.id, $hidden }
              WHEN ${ValidityCypher.holds(node)} THEN $node { .* }
            END AS $out
            """.trimIndent()
    }
}
