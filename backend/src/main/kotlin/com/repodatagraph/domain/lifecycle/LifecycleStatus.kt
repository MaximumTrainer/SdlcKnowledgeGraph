package com.repodatagraph.domain.lifecycle

import com.repodatagraph.domain.model.NodeKey
import com.repodatagraph.domain.model.Provenance
import java.time.Duration
import java.time.Instant

/** One connector's rules for what it stops reporting, as the status page shows them. */
data class ConnectorLifecycle(
    val name: String,
    val sourceSystem: String,
    val missingFromFullSync: MissingFromFullSync,
    val gracePeriod: Duration,
    val fullSyncIsComplete: Boolean,
) {
    /** Always true: see [TombstoneRules.requireSuccessfulRun]. */
    val requireSuccessfulRun: Boolean get() = true
}

/** The data lifecycle at a glance (#33, FR9): migrations, versioning, the archive and each connector's rules. */
data class LifecycleStatus(
    val migrations: MigrationStatus,
    val versioning: VersioningSettings,
    val archive: ArchiveStatus,
    val connectors: List<ConnectorLifecycle>,
) {
    val registryVersion: String get() = migrations.registryVersion
}

/**
 * A node's validity and values now (#33): when it began, when it ended if it has, and since when it
 * has held [props]. [resurrectedAt] is when a retired node was last stated again.
 */
data class CurrentValidity(
    val validFrom: Instant,
    val validTo: Instant?,
    val propsFrom: Instant,
    val props: Map<String, Any?>,
    val retiredReason: RetiredReason?,
    val resurrectedAt: Instant?,
) {
    val retired: Boolean get() = validTo != null
}

/**
 * Values a node held before a write changed them, or before it was retired: [retired] when the
 * version ended because the node did rather than because a value changed.
 */
data class NodeVersionView(
    val validFrom: Instant,
    val validTo: Instant,
    val retired: Boolean,
    val retiredReason: RetiredReason?,
    val props: Map<String, Any?>,
    val provenance: Provenance,
)

/** A node's history: what holds now, then each earlier version, newest first. */
data class NodeHistory(
    val key: NodeKey,
    val current: CurrentValidity,
    val versions: List<NodeVersionView>,
)
