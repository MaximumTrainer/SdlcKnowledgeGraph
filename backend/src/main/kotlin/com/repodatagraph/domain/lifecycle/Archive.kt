package com.repodatagraph.domain.lifecycle

import java.time.Duration
import java.time.Instant

/**
 * What an enabled archive does when it runs (#33, FR6). A rehearsal unless told otherwise: purging
 * needs this set to [PURGE] as well as the archive enabled, two settings rather than one.
 */
enum class ArchiveMode(
    val wireName: String,
) {
    /** Counts what it would take and changes nothing. The default. */
    DRY_RUN("dry-run"),

    /** Writes what it would take to the archive file and leaves the graph as it was. */
    EXPORT("export"),

    /** Writes the archive file, then deletes what it wrote from the graph. */
    PURGE("purge"),
    ;

    companion object {
        fun fromWire(value: String): ArchiveMode =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
                ?: throw IllegalArgumentException("lifecycle.archive.mode is one of ${entries.map { it.wireName }}, not '$value'")
    }
}

/**
 * The archive's configuration, `lifecycle.archive.*`. Off by default, and a dry run when on: nothing
 * is ever deleted unless someone has set both [enabled] and [mode].
 */
data class ArchiveSettings(
    val enabled: Boolean = false,
    val mode: ArchiveMode = ArchiveMode.DRY_RUN,
    val retention: Duration = Duration.ofDays(DEFAULT_RETENTION_DAYS),
    val schedule: String = DEFAULT_SCHEDULE,
    val directory: String = "./archive",
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "lifecycle.archive.retention must be positive, was $retention" }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 365L
        const val DEFAULT_SCHEDULE = "0 0 4 * * *"
    }
}

/** How many nodes and relationships an archive run would take, or took. */
data class ArchiveCounts(
    val nodes: Long,
    val edges: Long,
)

/**
 * One archived node or relationship, as one line of the archive file: its [kind], its [type] - the
 * node type or relationship type, so a reader can pick lines out without opening them - and the rest.
 */
data class ArchiveRecord(
    val kind: String,
    val data: Map<String, Any?>,
    val type: String? = data["type"]?.toString(),
)

/** What an archive run did, or in a dry run would do. */
data class ArchiveResult(
    val dryRun: Boolean,
    val mode: ArchiveMode,
    val cutoff: Instant,
    val wouldArchive: ArchiveCounts,
    val archived: ArchiveCounts? = null,
    val purged: ArchiveCounts? = null,
    val file: String? = null,
    val syncRunId: String? = null,
)

/** The archive as the status page shows it: whether it is on, how it runs, and what it would take now. */
data class ArchiveStatus(
    val enabled: Boolean,
    val mode: ArchiveMode,
    val retention: Duration,
    val schedule: String,
    val cutoff: Instant,
    val eligible: ArchiveCounts,
)

/** A run that would change the graph, asked of an archive nobody enabled. */
class ArchivalDisabledException : IllegalStateException("archival is disabled; set lifecycle.archive.enabled to turn it on") {
    val setting: String get() = "lifecycle.archive.enabled"
}
