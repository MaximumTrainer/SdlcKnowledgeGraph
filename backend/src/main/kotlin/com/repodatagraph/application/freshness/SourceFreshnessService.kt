package com.repodatagraph.application.freshness

import com.repodatagraph.application.connector.AdapterRegistry
import com.repodatagraph.domain.model.FreshnessPolicy
import com.repodatagraph.domain.model.SourceLag
import com.repodatagraph.domain.port.`in`.SourceFreshnessUseCase
import com.repodatagraph.domain.port.out.SyncRunStore
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How far behind each source system is (#93, FR-3): the end of its last successful sync run, of any
 * mode, against its freshness window.
 *
 * A source is reported when a run of it has succeeded or an enabled connector stamps it; the rest,
 * `manual` among them, have no sync to lag. A source whose connector has never succeeded is measured
 * from when this instance started watching, as a connector's own freshness is (#29), so a new
 * deployment is not called behind before its first run has had a window to happen.
 *
 * Read from the graph on every call, so the health component and the home page agree with each other
 * at the moment they are asked.
 */
@Service
class SourceFreshnessService(
    private val policy: FreshnessPolicy,
    private val registry: AdapterRegistry,
    private val syncRuns: SyncRunStore,
    private val clock: Clock,
) : SourceFreshnessUseCase {
    private val watchingSince: Instant = Instant.now(clock)

    override fun lag(): List<SourceLag> {
        val now = Instant.now(clock)
        val lastSuccess = syncRuns.lastSuccessBySource()
        val connected = registry.enabled().map { it.descriptor.sourceSystem }.toSet()
        return policy
            .windows()
            .filterKeys { it in lastSuccess || it in connected }
            .map { (source, window) ->
                val succeeded = lastSuccess[source]
                val lag = succeeded?.let { maxOf(Duration.between(it, now), Duration.ZERO) }
                val waited = lag ?: Duration.between(watchingSince, now)
                SourceLag(source, window, succeeded, lag, lagging = waited > window)
            }
    }
}
