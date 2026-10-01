package com.repodatagraph.application.connector

import com.repodatagraph.domain.port.out.connector.SyncMode

/**
 * A connector's run has finished and is recorded (#28, FR7). Published by [SyncService] after the
 * run's final status is written, so a listener reading the run back finds it ended.
 */
data class SyncRunCompleted(
    val connector: String,
    val runId: String,
    val mode: SyncMode,
    val status: RunStatus,
)
