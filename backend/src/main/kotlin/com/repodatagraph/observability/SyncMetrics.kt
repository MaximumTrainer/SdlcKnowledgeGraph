package com.repodatagraph.observability

import com.repodatagraph.domain.port.out.connector.SyncMode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * What connector runs are doing, as meters (#29, FR1; docs/OBSERVABILITY.md). A graph is only
 * trusted while it is fresh, and a connector that stopped syncing looks exactly like one with nothing
 * new to report until something says how long it has been.
 *
 * Every series is tagged with the connector, and all but the webhook count with its source system
 * too. Only a connector passed to [register] gets any: a name nobody registered is dropped rather
 * than becoming a series, so the `connector` label is bounded by the adapter registry however a
 * caller spells things. The other labels are enums.
 *
 * Freshness is kept in memory and seeded from the graph the first time it is scraped, so a scrape
 * costs one read per connector after a restart and none after that. A seed that fails, because Neo4j
 * is unreachable say, reads as NaN and is tried again at the next scrape rather than remembered.
 */
@Component
class SyncMetrics(
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    /** The meters of each registered connector. Nothing outside this map ever gets a series. */
    private val connectors = ConcurrentHashMap<String, ConnectorMeters>()

    /**
     * Makes a connector's series exist, at zero, before its first run, so an alert on a rate or on
     * freshness has something to read from startup. [running] is read on every scrape and
     * [lastSuccess] on the first, so the gauges hold no state of their own to go stale.
     */
    fun register(
        connector: String,
        sourceSystem: String,
        modes: Set<SyncMode>,
        running: () -> Boolean,
        lastSuccess: () -> Instant?,
    ) {
        val tags = Tags.of(CONNECTOR, connector, SOURCE_SYSTEM, sourceSystem)
        connectors[connector] = ConnectorMeters(meters, clock, tags, running, lastSuccess).also { it.registerAtZero(modes) }
    }

    /**
     * A run ended. [status] is the name of the run's final status, one of [FINISHED_STATUSES];
     * `RunStatus` belongs to the application layer, which depends on this package and not the
     * other way round.
     */
    @Suppress("LongParameterList") // What a run wrote, counted in one call so it cannot be half-recorded.
    fun runFinished(
        connector: String,
        mode: SyncMode,
        status: String,
        duration: Duration,
        nodes: Int,
        edges: Int,
        tombstones: Int,
    ) {
        val meters = connectors[connector] ?: return
        meters.runs(mode, status).increment()
        meters.duration(mode).record(duration)
        meters.counter(NODES_UPSERTED).increment(nodes.toDouble())
        meters.counter(EDGES_UPSERTED).increment(edges.toDouble())
        meters.counter(TOMBSTONES).increment(tombstones.toDouble())
    }

    /** A page was read from the connector, whether or not it could then be written. */
    fun page(connector: String) {
        connectors[connector]?.counter(PAGES)?.increment()
    }

    fun error(
        connector: String,
        kind: SyncErrorKind,
    ) {
        connectors[connector]?.errors(kind)?.increment()
    }

    /** What became of a webhook. A rejected one is also an error, since it may be an attack. */
    fun webhook(
        connector: String,
        result: WebhookResult,
    ) {
        val meters = connectors[connector] ?: return
        meters.webhooks(result).increment()
        if (result == WebhookResult.REJECTED) meters.errors(SyncErrorKind.WEBHOOK_SIGNATURE).increment()
    }

    /** A run of this connector succeeded [at] this moment, which is what freshness counts from. */
    fun succeeded(
        connector: String,
        at: Instant,
    ) {
        connectors[connector]?.lastSucceeded = Optional.of(at)
    }

    /** One connector's meters, all carrying its [tags]. */
    private class ConnectorMeters(
        private val meters: MeterRegistry,
        private val clock: Clock,
        private val tags: Tags,
        private val running: () -> Boolean,
        private val lastSuccess: () -> Instant?,
    ) {
        /** When it last succeeded; empty once the graph has said it never has, null until asked. */
        @Volatile
        var lastSucceeded: Optional<Instant>? = null

        fun registerAtZero(modes: Set<SyncMode>) {
            Gauge
                .builder(IN_PROGRESS) { if (running()) 1.0 else 0.0 }
                .description("1 while a run of this connector is going, otherwise 0")
                .tags(tags)
                .register(meters)
            Gauge
                .builder(FRESHNESS) { freshness() }
                .description("Seconds since this connector's last successful run finished; NaN if it never has")
                .baseUnit(SECONDS)
                .tags(tags)
                .register(meters)

            modes.forEach { mode ->
                FINISHED_STATUSES.forEach { runs(mode, it) }
                duration(mode)
            }
            listOf(NODES_UPSERTED, EDGES_UPSERTED, TOMBSTONES, PAGES).forEach(::counter)
            SyncErrorKind.entries
                .filter { it != SyncErrorKind.WEBHOOK_SIGNATURE || SyncMode.WEBHOOK in modes }
                .forEach(::errors)
            if (SyncMode.WEBHOOK in modes) WebhookResult.entries.forEach(::webhooks)
        }

        fun counter(name: String) = meters.counter(name, tags)

        fun runs(
            mode: SyncMode,
            status: String,
        ) = meters.counter(RUNS, tags.and(MODE, mode.name, STATUS, status))

        // The builder takes its buckets as varargs, and there are five, fixed at startup.
        @Suppress("SpreadOperator")
        fun duration(mode: SyncMode) =
            Timer
                .builder(DURATION)
                .description("How long connector runs took, from their first page to their last")
                .tags(tags.and(MODE, mode.name))
                .serviceLevelObjectives(*DURATION_BUCKETS)
                .register(meters)

        fun errors(kind: SyncErrorKind) = meters.counter(ERRORS, tags.and(KIND, kind.label))

        /** Without the source system: a webhook arrives for a connector, whatever it reads from. */
        fun webhooks(result: WebhookResult) = meters.counter(WEBHOOK_EVENTS, Tags.of(tags.connector(), Tag.of(RESULT, result.label)))

        private fun freshness(): Double {
            if (lastSucceeded == null) {
                // A failed read is not remembered, so the next scrape asks again; a success recorded
                // while the graph was being asked is newer than anything the graph could say.
                runCatching(lastSuccess).onSuccess { seeded ->
                    if (lastSucceeded == null) lastSucceeded = Optional.ofNullable(seeded)
                }
            }
            val last = lastSucceeded?.orElse(null) ?: return Double.NaN
            return Duration.between(last, Instant.now(clock)).toMillis() / MILLIS_PER_SECOND
        }

        private fun Tags.connector(): Tag = first { it.key == CONNECTOR }
    }

    companion object {
        /** The statuses a run can finish with, pre-registered for every mode a connector supports. */
        val FINISHED_STATUSES = listOf("SUCCESS", "PARTIAL", "FAILED")

        private const val RUNS = "sdlc.sync.runs"
        private const val DURATION = "sdlc.sync.duration"
        private const val NODES_UPSERTED = "sdlc.sync.nodes.upserted"
        private const val EDGES_UPSERTED = "sdlc.sync.edges.upserted"
        private const val TOMBSTONES = "sdlc.sync.tombstones"
        private const val PAGES = "sdlc.sync.pages"
        private const val ERRORS = "sdlc.sync.errors"
        private const val FRESHNESS = "sdlc.sync.freshness"
        private const val IN_PROGRESS = "sdlc.sync.in.progress"
        private const val WEBHOOK_EVENTS = "sdlc.webhook.events"

        private const val CONNECTOR = "connector"
        private const val SOURCE_SYSTEM = "sourceSystem"
        private const val MODE = "mode"
        private const val STATUS = "status"
        private const val KIND = "kind"
        private const val RESULT = "result"
        private const val SECONDS = "seconds"
        private const val MILLIS_PER_SECOND = 1000.0

        /** From a webhook's one write to a full sync of a large estate. */
        private val DURATION_BUCKETS =
            arrayOf(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(10),
            )
    }
}

/**
 * Why a run counted an error. There is no `throttle` yet: nothing tells a connector's rate limiting
 * apart from any other failure, and a kind nothing records would read as "never happens".
 */
enum class SyncErrorKind {
    /** A page could not be read, or could not be written; the run is partial. */
    PAGE,

    /** The run failed as a whole, or a webhook's write failed. */
    RUN,

    /** A webhook was refused because its signature did not check out. */
    WEBHOOK_SIGNATURE,
    ;

    val label: String = name.lowercase()
}

/**
 * What became of a webhook. `applied` means it became a run, whatever that run's status; `ignored`
 * that the connector found nothing in it or it was a redelivery of one already applied.
 */
enum class WebhookResult {
    APPLIED,
    IGNORED,
    REJECTED,
    ;

    val label: String = name.lowercase()
}
