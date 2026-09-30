package com.repodatagraph.domain.model

import java.time.Duration
import java.time.Instant

/**
 * How far behind one source system is (#93, FR-3): when a sync run of it last succeeded, how long ago
 * that was, and whether that is longer than the source's freshness window.
 *
 * [lastSuccessAt] and [lag] are null for a source that has never synced successfully rather than a
 * made-up age, as a connector's freshness is (#29): "never" and "long ago" call for different fixes.
 */
data class SourceLag(
    val source: String,
    val window: Duration,
    val lastSuccessAt: Instant?,
    val lag: Duration?,
    val lagging: Boolean,
)
