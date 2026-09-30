package com.repodatagraph.domain.port.`in`

import com.repodatagraph.domain.lifecycle.ArchiveResult
import com.repodatagraph.domain.lifecycle.LifecycleStatus
import com.repodatagraph.domain.lifecycle.MigrationApplyResult
import com.repodatagraph.domain.lifecycle.MigrationStatus
import com.repodatagraph.domain.lifecycle.NodeHistory
import com.repodatagraph.domain.model.NodeKey

/** The data lifecycle's administration (#33): its status, the ontology migrations, the archive, a node's history. */
interface LifecycleUseCase {
    fun status(): LifecycleStatus

    fun migrations(): MigrationStatus

    /** Applies every pending migration in order, each in its own transaction, stopping at the first that fails. */
    fun applyMigrations(): MigrationApplyResult

    /** Rehearses the archive when [dryRun], and otherwise runs it as configured. */
    fun archive(dryRun: Boolean): ArchiveResult

    /** The node's current validity and its earlier versions, or null when there is no such node. */
    fun history(key: NodeKey): NodeHistory?
}
