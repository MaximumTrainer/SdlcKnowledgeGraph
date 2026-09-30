package com.repodatagraph.domain.model

import java.time.Duration
import java.time.Instant

/**
 * How long each source system's facts stay fresh once it has stated them (#93, FR-1).
 *
 * A graph that lags reality is worse than no graph, because an agent trusts what it reads. So every
 * source has a window: a current fact its source has not stated again within it is stale, and a read
 * says so. [windows] overrides [defaultWindow] for the sources configuration names; each of them must
 * be a source the registry declares, so a typo in configuration fails at startup rather than leaving
 * a source on the default without anyone noticing.
 *
 * @param sources every source system the registry declares, in declaration order
 */
class FreshnessPolicy(
    val defaultWindow: Duration,
    windows: Map<String, Duration>,
    sources: List<String>,
) {
    private val declared: List<String> = sources.toList()
    private val overrides: Map<String, Duration> = windows.toMap()

    init {
        require(defaultWindow > Duration.ZERO) { "the default freshness window must be positive, not $defaultWindow" }
        val undeclared = overrides.keys - declared.toSet()
        require(undeclared.isEmpty()) {
            "freshness windows name ${undeclared.sorted()}, which sources.yaml does not declare; declared: $declared"
        }
        overrides.forEach { (source, window) ->
            require(window > Duration.ZERO) { "the freshness window of $source must be positive, not $window" }
        }
    }

    /** The window of [source]: its own where configuration gives one, otherwise the default. */
    fun windowFor(source: String): Duration = overrides[source] ?: defaultWindow

    /** Every declared source with the window it has, in declaration order, as `GET /api/v1/ontology` publishes it. */
    fun windows(): Map<String, Duration> = declared.associateWith(::windowFor)

    /**
     * Whether a fact with [provenance] is stale at [now]: current, and last stated by its source longer
     * ago than the source's window. A fact that has ended is history rather than a claim about now, so
     * it is never stale; one whose end is still to come is current until then.
     */
    fun isStale(
        provenance: Provenance,
        now: Instant,
    ): Boolean = isStale(provenance.sourceSystem, provenance.ingestedAt, provenance.validTo, now)

    /** [isStale] from the three facts it rests on, for a reader holding provenance in another shape. */
    fun isStale(
        sourceSystem: String,
        ingestedAt: Instant,
        validTo: Instant?,
        now: Instant,
    ): Boolean {
        val current = validTo?.isAfter(now) ?: true
        return current && Duration.between(ingestedAt, now) > windowFor(sourceSystem)
    }
}
