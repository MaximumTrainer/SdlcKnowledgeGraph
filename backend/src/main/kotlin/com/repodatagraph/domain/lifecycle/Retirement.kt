package com.repodatagraph.domain.lifecycle

import java.time.Duration
import java.time.Instant

/**
 * Why a fact stopped being current (#33, FR4), as the history and the API name it. Recorded on the
 * node when it is retired and cleared when it comes back.
 */
enum class RetiredReason(
    val wireName: String,
) {
    /** The source said the thing was deleted: a connector's tombstone. */
    SOURCE_DELETED("source-deleted"),

    /** The source said the thing was retired, archived or decommissioned, but still has it. */
    SOURCE_RETIRED("source-retired"),

    /** A complete full sync of its source no longer mentioned it (#150). */
    MISSING_FROM_SYNC("missing-from-sync"),

    /** A person closed it through the API (#93). */
    MANUAL("manual"),

    /**
     * It was merged into another node of its type, found to be the same thing (#98): its edges moved
     * there and its key resolves there. The node records which one.
     */
    MERGED("merged"),

    /**
     * A newer fact of the same kind replaced it (#90): a deployment of the same artifact family to the
     * same environment. It ended when the newer one began, not when the graph heard about it.
     */
    SUPERSEDED("superseded"),
    ;

    companion object {
        fun fromWire(value: String?): RetiredReason? = entries.firstOrNull { it.wireName == value }
    }
}

/** What a connector does with what a complete full sync stops reporting (#33, FR3). */
enum class MissingFromFullSync(
    val wireName: String,
) {
    /** Retire it: the source no longer has it. The default, and what #150 always did. */
    TOMBSTONE("tombstone"),

    /** Leave it: the source's full sync is not a statement about everything it once reported. */
    IGNORE("ignore"),
    ;

    companion object {
        fun fromWire(value: String): MissingFromFullSync =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "missing-from-full-sync is one of ${entries.map { it.wireName }}, not '$value'",
                )
    }
}

/**
 * A connector's rules for retiring what a complete full sync stops reporting (#33, FR3). When a run
 * may retire anything at all is reconciliation's decision (#150); these decide what, per connector.
 *
 * @param gracePeriod how long a fact the source stopped reporting stays current, measured from when
 *   the source last stated it, so one sync that misses something through a source's own flakiness
 *   retires nothing
 */
data class TombstoneRules(
    val missingFromFullSync: MissingFromFullSync = MissingFromFullSync.TOMBSTONE,
    val gracePeriod: Duration = Duration.ZERO,
) {
    init {
        require(!gracePeriod.isNegative) { "a grace period cannot be negative, was $gracePeriod" }
    }

    /**
     * Only a run that succeeded may retire anything, whatever the connector says: #150 made it so for
     * every connector, and a partial run retiring what it failed to read would erase the estate. It is
     * a property rather than a setting so the status page can say so, and nothing can turn it off.
     */
    val requireSuccessfulRun: Boolean get() = true

    /**
     * The instant before which a fact must last have been stated to be retired by a complete full run
     * that began at [runStartedAt], or null when this connector retires nothing that way.
     */
    fun retireStatedBefore(runStartedAt: Instant): Instant? =
        when (missingFromFullSync) {
            MissingFromFullSync.IGNORE -> null
            MissingFromFullSync.TOMBSTONE -> runStartedAt.minus(gracePeriod)
        }
}

/** Formats a duration as the API shows it: whole days as `P7D`, anything else as ISO-8601. */
fun Duration.toWire(): String = if (this.toSeconds() % SECONDS_PER_DAY == 0L && nano == 0) "P${toDays()}D" else toString()

private const val SECONDS_PER_DAY = 86_400L
