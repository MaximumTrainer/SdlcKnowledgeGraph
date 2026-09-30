package com.repodatagraph.application.lifecycle

import com.repodatagraph.application.connector.DeltaResult
import com.repodatagraph.application.connector.RunStatus
import com.repodatagraph.application.connector.SyncRunRecorder
import com.repodatagraph.domain.lifecycle.ArchivalDisabledException
import com.repodatagraph.domain.lifecycle.ArchiveMode
import com.repodatagraph.domain.lifecycle.ArchiveResult
import com.repodatagraph.domain.lifecycle.ArchiveSettings
import com.repodatagraph.domain.lifecycle.ArchiveStatus
import com.repodatagraph.domain.port.out.ArchiveStore
import com.repodatagraph.domain.port.out.ArchiveWriter
import com.repodatagraph.observability.LogEvents
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Moves closed facts older than the retention out of the graph (#33, FR6), in an order its safety
 * rests on.
 *
 * Off unless enabled, and a rehearsal unless its mode says otherwise: [ArchiveMode.DRY_RUN] only
 * counts, [ArchiveMode.EXPORT] writes the archive file and changes nothing, and only
 * [ArchiveMode.PURGE] deletes, after the file holding what it deletes has been written and closed.
 * A failure writing the file therefore purges nothing. A dry run asked for explicitly is always
 * allowed, enabled or not: it reads and changes nothing.
 *
 * A run that writes anything is recorded as a sync run of [CONNECTOR], so "what did the archive
 * take, and when" is answered where every other bulk change to the graph is.
 */
class ArchiveService(
    private val settings: ArchiveSettings,
    private val store: ArchiveStore,
    private val writer: ArchiveWriter,
    private val runs: SyncRunRecorder,
    private val clock: Clock,
) {
    fun status(): ArchiveStatus {
        val cutoff = cutoff(Instant.now(clock))
        return ArchiveStatus(settings.enabled, settings.mode, settings.retention, settings.schedule, cutoff, store.count(cutoff))
    }

    @Synchronized
    fun run(dryRun: Boolean): ArchiveResult {
        if (!dryRun && !settings.enabled) throw ArchivalDisabledException()
        val now = Instant.now(clock)
        val cutoff = cutoff(now)
        val eligible = store.count(cutoff)

        if (dryRun || settings.mode == ArchiveMode.DRY_RUN) {
            LogEvents.lifecycleArchiveFinished(settings.mode.wireName, true, cutoff, eligible.nodes.toInt(), eligible.edges.toInt())
            return ArchiveResult(dryRun = true, mode = settings.mode, cutoff = cutoff, wouldArchive = eligible)
        }

        val runId = "lifecycle-archive-" + UUID.randomUUID()
        val (location, archived) = writer.open(now).use { sink -> sink.location to store.export(cutoff, sink) }
        val purged = if (settings.mode == ArchiveMode.PURGE) store.purge(cutoff) else null

        runs.recordInternalRun(
            runId,
            CONNECTOR,
            RunStatus.SUCCESS,
            DeltaResult(nodesUpserted = archived.nodes.toInt(), edgesUpserted = archived.edges.toInt()),
            now,
            null,
        )
        LogEvents.lifecycleArchiveFinished(settings.mode.wireName, false, cutoff, archived.nodes.toInt(), archived.edges.toInt())
        return ArchiveResult(
            dryRun = false,
            mode = settings.mode,
            cutoff = cutoff,
            wouldArchive = eligible,
            archived = archived,
            purged = purged,
            file = location,
            syncRunId = runId,
        )
    }

    private fun cutoff(now: Instant): Instant = now.minus(settings.retention)

    companion object {
        /** The name an archive run is recorded under, as a sync run. */
        const val CONNECTOR = "lifecycle-archive"
    }
}
