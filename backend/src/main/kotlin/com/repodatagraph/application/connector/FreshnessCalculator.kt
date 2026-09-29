package com.repodatagraph.application.connector

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How fresh one connector is (#29, FR3).
 *
 * [age] is null when the connector has never succeeded, rather than a made-up number: "never" and
 * "a very long time ago" call for different fixes, and a caller should be able to tell them apart.
 */
data class ConnectorFreshness(
    val lastSuccessAt: Instant?,
    val age: Duration?,
    val threshold: Duration,
    val stale: Boolean,
)

/**
 * Decides whether a connector's last success is recent enough to trust what it wrote (#29, FR3/FR4).
 *
 * Stale means enabled and past the connector's threshold. A disabled connector is never stale: nobody
 * asked it to keep anything current, and a health check that went DOWN for it would be crying wolf.
 *
 * A connector that has never succeeded is measured from when this instance started watching it
 * instead of from its last success. Calling it stale at once would put every new deployment's health
 * DOWN before its first scheduled run had a chance to happen; waiting for ever would hide a connector
 * that has never worked at all. One threshold of grace is the difference.
 *
 * Read from the graph on every call rather than cached, so it agrees with what the connectors API
 * and the health component are asked at the same moment.
 */
@Component
class FreshnessCalculator(
    private val registry: AdapterRegistry,
    private val recorder: SyncRunRecorder,
    private val clock: Clock,
) {
    private val watchingSince: Instant = Instant.now(clock)

    fun of(registered: RegisteredConnector): ConnectorFreshness {
        val now = Instant.now(clock)
        val lastSuccess = recorder.lastSuccessAt(registered.name)
        val age = lastSuccess?.let { Duration.between(it, now) }
        val threshold = registered.freshnessThreshold
        val waited = age ?: Duration.between(watchingSince, now)
        return ConnectorFreshness(
            lastSuccessAt = lastSuccess,
            age = age,
            threshold = threshold,
            stale = registered.enabled && waited > threshold,
        )
    }

    /** The names of the enabled connectors that are stale now, in the registry's order. */
    fun staleConnectors(): List<String> = registry.enabled().filter { of(it).stale }.map { it.name }
}
