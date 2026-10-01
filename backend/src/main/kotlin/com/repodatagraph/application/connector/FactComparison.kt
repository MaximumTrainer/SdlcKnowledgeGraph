package com.repodatagraph.application.connector

import com.repodatagraph.domain.model.Provenance
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * Whether a fact about to be written says what the graph already holds (#86, FR-6): the same declared
 * properties, stated by the same source with the same confidence, and still current.
 *
 * Who says a fact and how sure they are is part of it, so the same values from another source, or
 * the same values now inferred, are a change. When a source saw it is not: a repository pushed to
 * again reports a later observedAt for a fact that did not move. Only declared properties are
 * compared, because the store keeps nothing else, and a property the write leaves out is left alone
 * by the store, so it is not a change either.
 */
internal fun sameFact(
    heldProps: Map<String, Any?>,
    held: Provenance,
    props: Map<String, Any?>,
    stated: Provenance,
    declared: Collection<String>,
): Boolean =
    held.current &&
        held.sourceSystem == stated.sourceSystem &&
        held.sourceId == stated.sourceId &&
        held.confidence == stated.confidence &&
        held.inferred == stated.inferred &&
        declared.filter { it in props }.all { name -> sameValue(heldProps[name], props[name]) }

/**
 * Two property values that mean the same, whatever types the store handed back: Neo4j returns every
 * integer as a Long, every instant as a zoned date-time, and a list as its own collection type.
 */
internal fun sameValue(
    held: Any?,
    stated: Any?,
): Boolean = normalised(held) == normalised(stated)

private fun normalised(value: Any?): Any? =
    when (value) {
        null -> null
        is Number -> value.toDouble()
        is Instant -> value
        is ZonedDateTime -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is Array<*> -> value.map(::normalised)
        is Collection<*> -> value.map(::normalised)
        is CharSequence -> value.toString()
        else -> value
    }
