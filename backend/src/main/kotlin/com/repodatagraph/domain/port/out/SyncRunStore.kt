package com.repodatagraph.domain.port.out

import com.repodatagraph.domain.model.SyncRun
import com.repodatagraph.domain.model.SyncRunPage
import com.repodatagraph.domain.model.SyncRunQuery
import java.time.Instant

/**
 * Reading and pruning the history of connector runs (#29, FR5 and FR6).
 *
 * A port of its own rather than more of [GraphStore], for the same reason as [FactLifecycle]:
 * [GraphStore] addresses nodes by key and lists them in key order, and the history is asked
 * different questions - newest first, filtered by time, counted, and deleted in bulk by age. Writing
 * runs stays with [GraphStore], through the recorder, so a run is written like any other node.
 */
interface SyncRunStore {
    /** The page of runs [query] asks for, newest start first, with how many match in all. */
    fun find(query: SyncRunQuery): SyncRunPage

    /** One run by its id, or null when there is none - never run, or pruned since. */
    fun findById(id: String): SyncRun?

    /**
     * Deletes up to [batchSize] runs that finished before [cutoff], with every relationship they
     * have, and returns how many runs went. A run that has not finished is never deleted, however
     * old: it may still be going, and the history is where someone would look for it.
     *
     * Only the runs go. The nodes a run PRODUCED stay, and still name it in their provenance, so
     * where a fact came from is not forgotten along with the run.
     *
     * One batch per call so the caller decides how far to go, and no single call holds more of the
     * graph in memory than a batch.
     */
    fun deleteFinishedBefore(
        cutoff: Instant,
        batchSize: Int,
    ): Int
}
