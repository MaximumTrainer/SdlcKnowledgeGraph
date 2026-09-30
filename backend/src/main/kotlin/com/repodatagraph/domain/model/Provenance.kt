package com.repodatagraph.domain.model

import java.time.Instant

/**
 * Where a fact came from, attached to every node and edge.
 *
 * A graph assembled from several systems is only trustworthy if each statement can be traced back to
 * whoever made it. Provenance is what lets a consumer distinguish "ServiceNow says this application
 * exists" from "a naming-convention rule guessed that these two things are related", and it is what
 * makes a bad sync reversible.
 *
 * @param sourceSystem the system of record, one the registry declares in sources.yaml (#117): "manual", "github",
 *   "servicenow", "aws" and so on
 * @param sourceId the identifier this fact has in that system, when it has one
 * @param observedAt when the source says the fact was true, which can be earlier than [ingestedAt]
 * @param confidence 1.0 when reported by the system of record, lower when derived by a rule
 * @param inferred true when a rule produced this rather than a system reporting it
 * @param validTo null while the fact is current; set when it is superseded
 * @param writtenBy the subject of the principal that made the write (#114): a user's token subject,
 *   or a service principal's registered name (#115); null for a fact a scheduled connector run wrote
 * @param principalType the kind of that principal, as its wire name ("user" or "service")
 * @param onBehalfOfTeam the key of the Team a service principal acted for (#115); null for a user
 * @param previousKeys the keys a node had before it was renamed through its alias (#88), oldest
 *   first. History rather than a statement of the latest write, so a store carries it across writes;
 *   always empty on an edge
 */
data class Provenance(
    val sourceSystem: String,
    val sourceId: String? = null,
    val ingestedAt: Instant,
    val observedAt: Instant? = null,
    val confidence: Double = FULL_CONFIDENCE,
    val inferred: Boolean = false,
    val validFrom: Instant,
    val validTo: Instant? = null,
    val syncRunId: String? = null,
    val writtenBy: String? = null,
    val principalType: String? = null,
    val onBehalfOfTeam: String? = null,
    val previousKeys: List<String> = emptyList(),
) {
    init {
        require(sourceSystem.isNotBlank()) { "provenance requires a sourceSystem" }
        require(confidence in 0.0..FULL_CONFIDENCE) { "confidence must be between 0.0 and 1.0, was $confidence" }
        require(validTo == null || !validTo.isBefore(validFrom)) { "validTo must not precede validFrom" }
    }

    /** True while this fact has not been superseded. */
    val current: Boolean get() = validTo == null

    /**
     * This write's provenance for a node it moves from key [from] to key [to] (#88): the keys the node
     * had under [previous], then [from], each once, and without [to], which is its key again.
     */
    fun afterRename(
        previous: Provenance,
        from: String,
        to: String,
    ): Provenance = copy(previousKeys = (previous.previousKeys + from).filter { it != to }.distinct())

    companion object {
        const val FULL_CONFIDENCE = 1.0
        const val MANUAL = "manual"

        /**
         * A fact stated directly through the API by a person or a service principal: fully trusted and not inferred, and
         * recording who stated it when [by] is known.
         */
        fun manual(
            now: Instant = Instant.now(),
            by: Principal? = null,
        ): Provenance = stated(MANUAL, now, by)

        /**
         * A fact stated through the API as [sourceSystem] (#117): `manual` for the principal's own word, or the system of
         * record it speaks for, which its scopes must allow. Fully trusted, not inferred, and naming who stated it.
         */
        fun stated(
            sourceSystem: String,
            now: Instant = Instant.now(),
            by: Principal? = null,
        ): Provenance =
            Provenance(
                sourceSystem = sourceSystem,
                ingestedAt = now,
                validFrom = now,
                writtenBy = by?.subject,
                principalType = by?.type?.wireName,
                onBehalfOfTeam = by?.onBehalfOfTeam,
            )
    }
}
