package com.repodatagraph.domain.model

import java.time.Duration
import java.time.Instant

/**
 * One recorded connector run, as the history reads it back (#29, FR5).
 *
 * Status and mode are the stored strings rather than enums: a run written by an older release, or
 * one whose status a later release renames, must still be listed rather than fail to read.
 *
 * @property id the run id, the SyncRun node's key - not its `id` property, which the store rewrites
 *   to the qualified "SyncRun:<id>"
 * @property details what the connector reported about the run beyond its counts, such as a summary
 *   per account or region. No connector records any yet, so it is empty until one does.
 */
data class SyncRun(
    val id: String,
    val connector: String,
    val sourceSystem: String?,
    val mode: String?,
    val status: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val nodesUpserted: Int,
    val edgesUpserted: Int,
    val tombstones: Int,
    val watermark: Instant?,
    val sourceId: String?,
    val error: String?,
    val details: Map<String, Any?> = emptyMap(),
) {
    /** How long the run took; null while it is still going, or if it never recorded a start. */
    val duration: Duration?
        get() = if (startedAt != null && finishedAt != null) Duration.between(startedAt, finishedAt) else null
}

/**
 * Which runs a caller wants, a page at a time, newest first.
 *
 * It refuses what cannot mean anything when it is built, so no store is ever asked for page -1, a
 * page of a million runs, or a window that ends before it starts. The window is half-open - from
 * inclusive, before exclusive - so adjacent windows never count the same run twice.
 */
data class SyncRunQuery(
    val connector: String? = null,
    val status: String? = null,
    val startedFrom: Instant? = null,
    val startedBefore: Instant? = null,
    val page: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        require(page >= 0) { "page counts from 0, not $page" }
        require(size in 1..MAX_SIZE) { "size must be between 1 and $MAX_SIZE, not $size" }
        require(startedFrom == null || startedBefore == null || startedFrom.isBefore(startedBefore)) {
            "from must be before to"
        }
    }

    /** How many runs come before this page. A Long, because page times size can pass Int.MAX_VALUE. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20

        /** The same ceiling the other list endpoints keep a page under, so no caller can ask for all. */
        const val MAX_SIZE = 100
    }
}

/** One page of runs, and how many match the query in all, so a caller can tell how far there is to go. */
data class SyncRunPage(
    val items: List<SyncRun>,
    val totalElements: Long,
) {
    fun totalPages(size: Int): Long = (totalElements + size - 1) / size
}
