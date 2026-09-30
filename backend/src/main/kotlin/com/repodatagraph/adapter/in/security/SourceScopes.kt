package com.repodatagraph.adapter.`in`.security

import com.repodatagraph.domain.model.Provenance

/**
 * The scopes a write naming a source system needs (#117, FR-2): `graph:write` to change the graph
 * at all, and for any source but `manual` also `graph:write:<source>`, so a connector can assert only
 * its own system's facts and a principal granted none can state only its own word.
 *
 * `graph:write` is also what [ScopePolicy] demands of every write, so a source scope never stands in
 * for it: a token holding `graph:write:github` alone is refused by [ScopeGate] before a source is read.
 */
object SourceScopes {
    private const val PREFIX = "graph:write:"

    /** The scope that lets a principal write as [sourceSystem]. */
    fun scopeFor(sourceSystem: String): String = PREFIX + sourceSystem

    /** Everything a write naming [sourceSystem] needs, sorted as a refusal reports it. */
    fun requiredFor(sourceSystem: String): List<String> =
        if (sourceSystem == Provenance.MANUAL) {
            listOf(GraphScope.WRITE.value)
        } else {
            listOf(GraphScope.WRITE.value, scopeFor(sourceSystem)).sorted()
        }
}
