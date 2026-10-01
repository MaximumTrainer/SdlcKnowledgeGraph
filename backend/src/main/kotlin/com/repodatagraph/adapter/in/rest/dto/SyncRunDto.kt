package com.repodatagraph.adapter.`in`.rest.dto

import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.model.SyncRunQuery

/**
 * One run as a list shows it (#29, FR5): enough to scan a table and pick a row.
 *
 * Times are ISO-8601 instants and the duration is milliseconds, so a table can sort and a chart can
 * plot without parsing a duration format. `error` is cut to [ERROR_SNIPPET] characters, ending in an
 * ellipsis when it was cut: a stack of runs that all failed the same long way should not make the
 * page megabytes. The whole error is on the run itself.
 */
data class SyncRunSummary(
    val id: String,
    val connector: String,
    val sourceSystem: String?,
    val mode: String?,
    val status: String?,
    val startedAt: String?,
    val finishedAt: String?,
    /** Null while the run is still going. */
    val durationMs: Long?,
    val nodesUpserted: Int,
    val edgesUpserted: Int,
    val tombstones: Int,
    val error: String?,
    /** What the run created or changed (#86); null for a run recorded before this was counted. */
    val written: Int?,
    /** What the run stated again exactly as the graph held it. */
    val unchanged: Int?,
    /** What the run could not read or write. */
    val failed: Int?,
    /** The version of the connector that ran. */
    val connectorVersion: String?,
) {
    companion object {
        const val ERROR_SNIPPET = 200

        fun from(run: SyncRun) =
            SyncRunSummary(
                id = run.id,
                connector = run.connector,
                sourceSystem = run.sourceSystem,
                mode = run.mode,
                status = run.status,
                startedAt = run.startedAt?.toString(),
                finishedAt = run.finishedAt?.toString(),
                durationMs = run.duration?.toMillis(),
                nodesUpserted = run.nodesUpserted,
                edgesUpserted = run.edgesUpserted,
                tombstones = run.tombstones,
                error = run.error?.let(::snippet),
                written = run.written,
                unchanged = run.unchanged,
                failed = run.failed,
                connectorVersion = run.connectorVersion,
            )

        private fun snippet(error: String): String = if (error.length <= ERROR_SNIPPET) error else error.take(ERROR_SNIPPET - 1) + "…"
    }
}

/**
 * A page of runs, newest first. Offset paging rather than the cursor the node list uses: the history
 * is browsed by page number in a table, and a run is only ever added at the front, so an offset
 * shifts by at most the runs that started while someone was reading.
 */
data class SyncRunPageResponse(
    val items: List<SyncRunSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
) {
    companion object {
        fun from(
            result: SyncRunPage,
            query: SyncRunQuery,
        ) = SyncRunPageResponse(
            items = result.items.map { SyncRunSummary.from(it) },
            page = query.page,
            size = query.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages(query.size),
        )
    }
}

/**
 * One run in full: the summary's fields with the whole error, where an incremental run should start
 * next, the delivery that caused a webhook run, and `details`.
 *
 * `details` is for what a connector reports beyond its counts, such as a summary per account or
 * region. No connector records any yet, so it is an empty object rather than absent: a client can
 * read it today and get more from it later without changing.
 */
data class SyncRunDetail(
    val id: String,
    val connector: String,
    val sourceSystem: String?,
    val mode: String?,
    val status: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val durationMs: Long?,
    val nodesUpserted: Int,
    val edgesUpserted: Int,
    val tombstones: Int,
    val watermark: String?,
    val sourceId: String?,
    val error: String?,
    val details: Map<String, Any?>,
    val written: Int?,
    val unchanged: Int?,
    val failed: Int?,
    val connectorVersion: String?,
) {
    companion object {
        fun from(run: SyncRun) =
            SyncRunDetail(
                id = run.id,
                connector = run.connector,
                sourceSystem = run.sourceSystem,
                mode = run.mode,
                status = run.status,
                startedAt = run.startedAt?.toString(),
                finishedAt = run.finishedAt?.toString(),
                durationMs = run.duration?.toMillis(),
                nodesUpserted = run.nodesUpserted,
                edgesUpserted = run.edgesUpserted,
                tombstones = run.tombstones,
                watermark = run.watermark?.toString(),
                sourceId = run.sourceId,
                error = run.error,
                details = run.details,
                written = run.written,
                unchanged = run.unchanged,
                failed = run.failed,
                connectorVersion = run.connectorVersion,
            )
    }
}
