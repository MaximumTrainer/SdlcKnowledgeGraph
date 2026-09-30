package com.repodatagraph.domain.model

import com.repodatagraph.domain.exception.InvalidQueryParameterException
import java.time.Instant

/**
 * When a fact holds (#93, FR-4): over the half-open interval `[validFrom, validTo)`, from the instant
 * it began up to but not including the instant it ended, with no end while it is current. Half-open so
 * that of two facts where one ended as the next began - one deployment replacing another - exactly
 * one holds at any instant.
 *
 * The one statement of the rule outside Cypher; the store's as-of reads say the same thing in its
 * query language (`Neo4jGraphStore`). A later history of property versions (#33) selects versions by
 * the same interval.
 */
object ValidityWindow {
    fun contains(
        provenance: Provenance,
        at: Instant,
    ): Boolean = !provenance.validFrom.isAfter(at) && (provenance.validTo?.isAfter(at) ?: true)
}

/** The name of the query parameter, and GraphQL argument, that reads the graph as of an instant. */
const val AS_OF = "asOf"

/**
 * An `asOf` parameter (#93, FR-4): absent means the current view, and anything present must be an
 * ISO-8601 instant, such as `2026-09-30T01:30:00Z`, or it is refused naming the parameter. A date on
 * its own is refused rather than read as midnight in some zone: the caller has to say which instant.
 */
fun asOfParameter(value: String?): Instant? {
    if (value == null) return null
    return runCatching { Instant.parse(value.trim()) }.getOrNull()
        ?: throw InvalidQueryParameterException(AS_OF, "$AS_OF must be an ISO-8601 instant, such as 2026-09-30T01:30:00Z, was '$value'")
}
