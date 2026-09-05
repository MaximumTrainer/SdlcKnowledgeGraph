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
 * @param sourceSystem the system of record, for example "github", "servicenow:prod", "aws:123456789012" or "manual"
 * @param sourceId the identifier this fact has in that system, when it has one
 * @param observedAt when the source says the fact was true, which can be earlier than [ingestedAt]
 * @param confidence 1.0 when reported by the system of record, lower when derived by a rule
 * @param inferred true when a rule produced this rather than a system reporting it
 * @param validTo null while the fact is current; set when it is superseded
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
) {
    init {
        require(sourceSystem.isNotBlank()) { "provenance requires a sourceSystem" }
        require(confidence in 0.0..FULL_CONFIDENCE) { "confidence must be between 0.0 and 1.0, was $confidence" }
        require(validTo == null || !validTo.isBefore(validFrom)) { "validTo must not precede validFrom" }
    }

    /** True while this fact has not been superseded. */
    val current: Boolean get() = validTo == null

    companion object {
        const val FULL_CONFIDENCE = 1.0
        const val MANUAL = "manual"

        /** A fact stated directly through the API by a person: fully trusted and not inferred. */
        fun manual(now: Instant = Instant.now()): Provenance = Provenance(sourceSystem = MANUAL, ingestedAt = now, validFrom = now)
    }
}
