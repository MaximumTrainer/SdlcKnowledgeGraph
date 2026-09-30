package com.repodatagraph.adapter.out.neo4j

/**
 * The validity rules of #93 in Cypher, for any node or relationship variable, so every query that
 * reads as of an instant or restates a fact says them the same way. Their plain statements are
 * [com.repodatagraph.domain.model.ValidityWindow] and
 * [com.repodatagraph.domain.model.Provenance.validFromFor]; a history of property versions (#33) is
 * meant to select by the same predicate.
 *
 * The variable names are this adapter's own, never a caller's text.
 */
internal object ValidityCypher {
    /** The parameter [holds] compares against, bound as a stored temporal value. */
    const val AS_OF = "asOf"

    /**
     * True while [variable]'s `[validFrom, validTo)` contains `$asOf`. A fact written before validity
     * was recorded has no validFrom and reads back as beginning at the epoch, so it is treated as
     * having held since then here too.
     */
    fun holds(variable: String): String =
        "(coalesce($variable.prov_validFrom, datetime('1970-01-01T00:00:00Z')) <= \$$AS_OF" +
            " AND ($variable.prov_validTo IS NULL OR $variable.prov_validTo > \$$AS_OF))"

    /**
     * Captures, before a write, when [variable] began and whether it was current, for [keepBegan]. It
     * follows a `WITH [variable],` of the statement's own.
     */
    fun began(variable: String): String = "$variable.prov_validFrom AS began, $variable.prov_validTo IS NULL AS wasCurrent"

    /**
     * After `SET [variable] += $props`, puts back the validFrom the fact began with when it was current
     * or this write closes it, and otherwise - a new fact, or a closed one stated as current again -
     * leaves the write's own.
     */
    fun keepBegan(variable: String): String =
        "SET $variable.prov_validFrom = CASE WHEN began IS NOT NULL AND (wasCurrent OR \$props.prov_validTo IS NOT NULL)" +
            " THEN began ELSE \$props.prov_validFrom END"
}
