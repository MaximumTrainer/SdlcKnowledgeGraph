package com.repodatagraph.application.freshness

import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.model.Provenance
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Whether a fact read back now is stale (#93, FR-1): the freshness policy asked at this moment.
 *
 * Computed on every read rather than stored, because staleness is a fact about the reader's now: a
 * stored flag would itself go stale the moment it was written.
 */
@Component
class FactFreshness(
    private val policy: FreshnessPolicy,
    private val clock: Clock,
) {
    fun stale(provenance: Provenance): Boolean = policy.isStale(provenance, Instant.now(clock))

    fun stale(
        sourceSystem: String,
        ingestedAt: Instant,
        validTo: Instant?,
    ): Boolean = policy.isStale(sourceSystem, ingestedAt, validTo, Instant.now(clock))
}
